package com.brewslot.loyalty.application

import com.brewslot.common.Money
import com.brewslot.loyalty.domain.Accounts
import com.brewslot.loyalty.domain.Coupon
import com.brewslot.loyalty.domain.CouponRejection
import com.brewslot.loyalty.domain.CouponStatus
import com.brewslot.loyalty.domain.LotAllocator
import com.brewslot.loyalty.domain.Posting
import com.brewslot.loyalty.domain.TxType
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PointsEarned
import com.brewslot.messaging.contract.PointsRedeemed
import com.brewslot.messaging.contract.PointsRedemptionFailed
import com.brewslot.messaging.contract.PointsRedemptionReversed
import com.brewslot.messaging.outbox.Outbox
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 포인트 적립·사용·사용취소·소멸.
 *
 * 모든 쓰기는 (1) 지갑 행 잠금 → (2) lot 변경 → (3) 복식부기 분개 → (4) 지갑 잔액 캐시 갱신 → (5) 이벤트 Outbox 순서다.
 * 주문 단위 멱등성은 ledger_transaction(order_id, tx_type) 유니크 인덱스로 보장한다.
 */
@Service
class PointService(
    private val ledger: PointLedger,
    private val coupons: CouponRepository,
    private val outbox: Outbox,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 픽업 완료 시 적립. 포인트로 결제한 부분은 적립 대상이 아니다(카드 결제액 기준). */
    @Transactional
    fun earn(orderId: UUID, memberId: Long, brandId: Long, cardAmount: Long) {
        if (ledger.findByOrder(orderId, TxType.EARN) != null) return
        val (rateBps, expiryDays) = ledger.policy(brandId)
        val amount = Money(cardAmount).percentOf(rateBps, RoundingMode.DOWN).won
        if (amount <= 0) return
        val now = clock.instant()
        ledger.lockWallet(memberId, brandId, now)
        val txId = ledger.record(
            TxType.EARN,
            orderId,
            memberId,
            brandId,
            null,
            amount,
            "주문 적립 ${rateBps / 100.0}%",
            Posting.transfer(Accounts.brandFunding(brandId), Accounts.memberWallet(memberId, brandId), amount),
            now,
        )
        val expiresAt = now.plus(Duration.ofDays(expiryDays.toLong()))
        ledger.createLot(memberId, brandId, txId, amount, now, expiresAt)
        ledger.adjustWallet(memberId, brandId, amount, now)
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsEarned(orderId.toString(), memberId, brandId, amount, expiresAt, now),
        )
    }

    /** 운영/프로모션 지급 (브랜드 부담) */
    @Transactional
    fun grant(memberId: Long, brandId: Long, amount: Long, expiryDays: Int, memo: String): Long {
        require(amount > 0) { "amount must be positive" }
        val now = clock.instant()
        ledger.lockWallet(memberId, brandId, now)
        val txId = ledger.record(
            TxType.GRANT,
            null,
            memberId,
            brandId,
            null,
            amount,
            memo,
            Posting.transfer(Accounts.brandFunding(brandId), Accounts.memberWallet(memberId, brandId), amount),
            now,
        )
        ledger.createLot(memberId, brandId, txId, amount, now, now.plus(Duration.ofDays(expiryDays.toLong())))
        ledger.adjustWallet(memberId, brandId, amount, now)
        return txId
    }

    /**
     * Saga 1단계: 주문의 혜택(쿠폰 + 포인트)을 한 로컬 트랜잭션으로 사용한다. 성공/실패 모두 이벤트로 응답한다.
     *
     * 검사를 모두 끝낸 뒤에 쓰기를 시작한다 — 쿠폰은 통과했는데 포인트가 부족한 경우 아무것도 바뀌지 않는다.
     * 잠금 순서는 항상 "지갑 → 쿠폰" (보상도 같은 순서).
     */
    @Transactional
    fun redeem(
        orderId: UUID,
        memberId: Long,
        brandId: Long,
        storeId: Long,
        amount: Long,
        couponId: UUID? = null,
        couponAmount: Long = 0,
        orderTotal: Long = 0,
    ) {
        val now = clock.instant()
        if (ledger.findByOrder(orderId, TxType.REDEEM) != null || ledger.findByOrder(orderId, TxType.COUPON_REDEEM) != null) {
            log.info("redeem already applied for order {}", orderId)
            return
        }
        val balance = ledger.lockWallet(memberId, brandId, now)

        val coupon = couponId?.let { id ->
            val c = coupons.findForUpdate(id)
            val rejection = c?.rejectionFor(memberId, brandId, couponAmount, orderTotal, now) ?: if (c == null) CouponRejection.COUPON_NOT_FOUND else null
            if (rejection != null) return fail(orderId, rejection.name, balance, now)
            c
        }
        val consumptions = if (amount > 0) {
            val lots = ledger.usableLots(memberId, brandId, now)
            LotAllocator.allocate(lots, amount, now) ?: return fail(orderId, "INSUFFICIENT_BALANCE", lots.sumOf { it.remaining }, now)
        } else {
            emptyList()
        }

        if (coupon != null) {
            ledger.record(
                TxType.COUPON_REDEEM,
                orderId,
                memberId,
                brandId,
                storeId,
                coupon.discountAmount,
                "쿠폰 사용 (캠페인 #${coupon.campaignId})",
                Posting.transfer(Accounts.brandPromotion(brandId), Accounts.storeClearing(storeId), coupon.discountAmount),
                now,
            )
            coupons.markUsed(coupon.id, orderId, now)
        }
        if (amount > 0) {
            val txId = ledger.record(
                TxType.REDEEM,
                orderId,
                memberId,
                brandId,
                storeId,
                amount,
                "주문 결제 사용",
                Posting.transfer(Accounts.memberWallet(memberId, brandId), Accounts.storeClearing(storeId), amount),
                now,
            )
            ledger.consume(txId, consumptions)
            ledger.adjustWallet(memberId, brandId, -amount, now)
        }
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsRedeemed(orderId.toString(), memberId, brandId, storeId, amount, now, coupon?.id?.toString(), coupon?.discountAmount ?: 0),
        )
    }

    private fun fail(orderId: UUID, reason: String, available: Long, now: Instant) {
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsRedemptionFailed(orderId.toString(), reason, available, now),
        )
    }

    /**
     * 보상. 사용 내역이 없거나 이미 취소됐으면 아무것도 하지 않는다.
     * 포인트는 차감했던 바로 그 lot 들로, 쿠폰은 유효기간이 남았으면 다시 쓸 수 있는 상태로 되돌린다.
     */
    @Transactional
    fun reverseRedemption(orderId: UUID, reason: String) {
        val redeem = ledger.findByOrder(orderId, TxType.REDEEM)
        val couponTx = ledger.findByOrder(orderId, TxType.COUPON_REDEEM)
        val base = redeem ?: couponTx ?: return
        if (ledger.findByOrder(orderId, TxType.REVERSE_REDEEM) != null || ledger.findByOrder(orderId, TxType.REVERSE_COUPON) != null) return
        val now = clock.instant()
        ledger.lockWallet(base.memberId, base.brandId, now)
        if (redeem != null) {
            ledger.record(
                TxType.REVERSE_REDEEM,
                orderId,
                redeem.memberId,
                redeem.brandId,
                redeem.storeId,
                redeem.amount,
                "사용 취소: $reason",
                Posting.transfer(
                    Accounts.storeClearing(redeem.storeId!!),
                    Accounts.memberWallet(redeem.memberId, redeem.brandId),
                    redeem.amount,
                ),
                now,
            )
            ledger.restore(ledger.consumptionsOf(redeem.id))
            ledger.adjustWallet(redeem.memberId, redeem.brandId, redeem.amount, now)
        }
        var restoredCoupon: Coupon? = null
        var restoredAs: CouponStatus? = null
        if (couponTx != null) {
            ledger.record(
                TxType.REVERSE_COUPON,
                orderId,
                couponTx.memberId,
                couponTx.brandId,
                couponTx.storeId,
                couponTx.amount,
                "쿠폰 사용 취소: $reason",
                Posting.transfer(Accounts.storeClearing(couponTx.storeId!!), Accounts.brandPromotion(couponTx.brandId), couponTx.amount),
                now,
            )
            restoredCoupon = coupons.findByOrderForUpdate(orderId)
            restoredCoupon?.let { c ->
                restoredAs = c.restoredStatus(now)
                coupons.restore(c.id, restoredAs!!)
            }
        }
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsRedemptionReversed(
                orderId.toString(),
                base.memberId,
                base.brandId,
                base.storeId!!,
                redeem?.amount ?: 0,
                now,
                restoredCoupon?.id?.toString(),
                couponTx?.amount ?: 0,
                restoredAs?.name,
            ),
        )
    }

    /** 한 지갑의 만료 lot 을 소멸 처리한다. 반환: 소멸 금액 */
    @Transactional
    fun expireWallet(memberId: Long, brandId: Long): Long {
        val now = clock.instant()
        ledger.lockWallet(memberId, brandId, now)
        var total = 0L
        ledger.expiredLotsForUpdate(memberId, brandId, now).forEach { lot ->
            ledger.record(
                TxType.EXPIRE,
                null,
                memberId,
                brandId,
                null,
                lot.remaining,
                "lot#${lot.id} 유효기간 만료",
                Posting.transfer(Accounts.memberWallet(memberId, brandId), Accounts.brandBreakage(brandId), lot.remaining),
                now,
            )
            ledger.zeroLot(lot.id)
            total += lot.remaining
        }
        if (total > 0) ledger.adjustWallet(memberId, brandId, -total, now)
        return total
    }
}
