package com.brewslot.settlement

import com.brewslot.settlement.domain.EntryKind
import com.brewslot.settlement.domain.IssueType
import com.brewslot.settlement.domain.LedgerCardLine
import com.brewslot.settlement.domain.PgLine
import com.brewslot.settlement.domain.Reconciler
import com.brewslot.settlement.domain.SettlementCalculator
import com.brewslot.settlement.domain.SettlementEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SettlementCalculatorTest {
    private val date = LocalDate.parse("2026-10-20")

    private fun e(kind: EntryKind, amount: Long, day: LocalDate = date) =
        SettlementEntry(0, 101, kind, amount, if (kind.isCard) SettlementCalculator.cardFee(amount, 220) else 0, day)

    @Test
    fun `PG 수수료는 건별로 반올림한다 - 합계에 한 번 곱하면 PG 파일과 1원 단위로 어긋난다`() {
        val entries = listOf(e(EntryKind.CARD_SALE, 4_525), e(EntryKind.CARD_SALE, 4_525)) // 99.55 → 100, 두 건 200
        val draft = SettlementCalculator.draft(101, date, entries, 100)
        assertThat(draft.pgFee).isEqualTo(200)
        assertThat(9_050L * 220 / 10_000.0).isEqualTo(199.1) // 합계에 곱했다면 199
    }

    @Test
    fun `순매출·수수료·지급액 - 포인트 매출도 매장 매출이고, 환불은 수수료도 되돌린다`() {
        val entries = listOf(
            e(EntryKind.CARD_SALE, 10_000), // fee 220
            e(EntryKind.CARD_SALE, 5_500), // fee 121
            e(EntryKind.CARD_REFUND, -5_500), // fee -121
            e(EntryKind.POINT_SALE, 3_000),
            e(EntryKind.POINT_REVERSAL, -1_000),
            e(EntryKind.CARD_SALE, 2_000, date.minusDays(1)), // 전날 거래가 늦게 도착 → 이월
        )
        val d = SettlementCalculator.draft(101, date, entries, 100)
        assertThat(d.netSales).isEqualTo(14_000)
        assertThat(d.pgFee).isEqualTo(264) // 220 + 121 - 121 + 44
        assertThat(d.platformFee).isEqualTo(140)
        assertThat(d.payout).isEqualTo(14_000 - 264 - 140)
        assertThat(d.carriedOverCount).isEqualTo(1)
    }

    @Test
    fun `대사 - 누락·금액 불일치·수수료 불일치를 각각 구분한다`() {
        val pg = listOf(PgLine("t1", "o1", 10_000, 220), PgLine("t2", "o2", 5_000, 110), PgLine("t3", "o3", 3_000, 66), PgLine("ghost", "o9", 7_000, 154))
        val ledger = listOf(
            LedgerCardLine("t1", "o1", 10_000, 220),
            LedgerCardLine("t2", "o2", 5_500, 121),
            LedgerCardLine("t3", "o3", 3_000, 65),
            LedgerCardLine("t4", "o4", 1_000, 22),
        )
        val result = Reconciler.reconcile(pg, ledger)
        assertThat(result.matched).isEqualTo(1)
        assertThat(result.issues.map { it.type to it.transactionId }).containsExactlyInAnyOrder(
            IssueType.AMOUNT_MISMATCH to "t2",
            IssueType.FEE_MISMATCH to "t3",
            IssueType.MISSING_IN_LEDGER to "ghost",
            IssueType.MISSING_IN_PG to "t4",
        )
    }
}
