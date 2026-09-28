package com.brewslot.settlement.domain

import com.brewslot.common.Money
import java.math.RoundingMode
import java.time.LocalDate

enum class EntryKind(val isCard: Boolean) {
    CARD_SALE(true),
    CARD_REFUND(true),
    POINT_SALE(false),
    POINT_REVERSAL(false),
    COUPON_SALE(false),
    COUPON_REVERSAL(false),

    /** 매장 변경으로 빠져나간 매출(음수). pg_fee 도 음수로 함께 옮긴다. */
    TRANSFER_OUT(false),

    /** 매장 변경으로 들어온 매출(양수) */
    TRANSFER_IN(false),
}

data class SettlementEntry(
    val id: Long,
    val storeId: Long,
    val kind: EntryKind,
    val amount: Long,
    val pgFee: Long,
    val businessDate: LocalDate,
)

data class StatementDraft(
    val storeId: Long,
    val settlementDate: LocalDate,
    val cardSales: Long,
    val cardRefunds: Long,
    val pointSales: Long,
    val pointReversals: Long,
    val netSales: Long,
    val pgFee: Long,
    val platformFee: Long,
    val payout: Long,
    val entryCount: Int,
    val carriedOverCount: Int,
    val couponSales: Long = 0,
    val couponReversals: Long = 0,
    val transfersIn: Long = 0,
    val transfersOut: Long = 0,
)

/**
 * 매장 정산서 계산 규칙 (순수 함수).
 *
 * - 순매출 = 카드매출 + 카드환불(음수) + 포인트매출 + 포인트사용취소(음수) + 쿠폰매출 + 쿠폰사용취소(음수)
 *   포인트·쿠폰으로 결제된 금액도 매장 매출이다. 그 비용은 브랜드가 부담하며 플랫폼이 매장에 먼저 지급한다.
 * - PG 수수료 = 거래 건별로 PG 가 계산한 수수료의 합 (건별 반올림 — PG 정산 파일과 1원 단위로 일치해야 함)
 * - 플랫폼 수수료 = 순매출 × 요율, 원 미만 절사(가맹점 유리)
 * - 지급액 = 순매출 − PG 수수료 − 플랫폼 수수료 (DB CHECK 제약으로도 강제)
 */
object SettlementCalculator {
    fun cardFee(amount: Long, pgFeeBps: Int): Long {
        val fee = Money(kotlin.math.abs(amount)).percentOf(pgFeeBps, RoundingMode.HALF_UP).won
        return if (amount < 0) -fee else fee
    }

    fun draft(storeId: Long, settlementDate: LocalDate, entries: List<SettlementEntry>, platformFeeBps: Int): StatementDraft {
        require(entries.all { it.storeId == storeId })

        fun sum(kind: EntryKind) = entries.filter { it.kind == kind }.sumOf { it.amount }
        val net = entries.sumOf { it.amount }
        val pgFee = entries.sumOf { it.pgFee }
        val platformFee = Money(net).percentOf(platformFeeBps, RoundingMode.DOWN).won
        return StatementDraft(
            storeId = storeId,
            settlementDate = settlementDate,
            cardSales = sum(EntryKind.CARD_SALE),
            cardRefunds = sum(EntryKind.CARD_REFUND),
            pointSales = sum(EntryKind.POINT_SALE),
            pointReversals = sum(EntryKind.POINT_REVERSAL),
            netSales = net,
            pgFee = pgFee,
            platformFee = platformFee,
            payout = net - pgFee - platformFee,
            entryCount = entries.size,
            carriedOverCount = entries.count { it.businessDate.isBefore(settlementDate) },
            couponSales = sum(EntryKind.COUPON_SALE),
            couponReversals = sum(EntryKind.COUPON_REVERSAL),
            transfersIn = sum(EntryKind.TRANSFER_IN),
            transfersOut = sum(EntryKind.TRANSFER_OUT),
        )
    }
}
