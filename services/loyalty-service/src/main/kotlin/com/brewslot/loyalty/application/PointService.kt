package com.brewslot.loyalty.application

import com.brewslot.common.Money
import com.brewslot.loyalty.domain.Accounts
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

    /** Saga 1단계. 성공/실패 모두 이벤트로 응답한다. */
    @Transactional
    fun redeem(orderId: UUID, memberId: Long, brandId: Long, storeId: Long, amount: Long) {
        val now = clock.instant()
        if (ledger.findByOrder(orderId, TxType.REDEEM) != null) {
            log.info("redeem already applied for order {}", orderId)
            return
        }
        ledger.lockWallet(memberId, brandId, now)
        val lots = ledger.usableLots(memberId, brandId, now)
        val consumptions = LotAllocator.allocate(lots, amount, now)
        if (consumptions == null) {
            outbox.publish(
                Topics.LOYALTY_EVENTS,
                orderId.toString(),
                PointsRedemptionFailed(orderId.toString(), "INSUFFICIENT_BALANCE", lots.sumOf { it.remaining }, now),
            )
            return
        }
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
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsRedeemed(orderId.toString(), memberId, brandId, storeId, amount, now),
        )
    }

    /** 보상. 사용 내역이 없거나 이미 취소됐으면 아무것도 하지 않는다. 차감했던 바로 그 lot 들로 되돌린다. */
    @Transactional
    fun reverseRedemption(orderId: UUID, reason: String) {
        val redeem = ledger.findByOrder(orderId, TxType.REDEEM) ?: return
        if (ledger.findByOrder(orderId, TxType.REVERSE_REDEEM) != null) return
        val now = clock.instant()
        ledger.lockWallet(redeem.memberId, redeem.brandId, now)
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
        outbox.publish(
            Topics.LOYALTY_EVENTS,
            orderId.toString(),
            PointsRedemptionReversed(orderId.toString(), redeem.memberId, redeem.brandId, redeem.storeId, redeem.amount, now),
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
