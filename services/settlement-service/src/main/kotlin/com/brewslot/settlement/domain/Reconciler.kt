package com.brewslot.settlement.domain

enum class IssueType { MISSING_IN_LEDGER, MISSING_IN_PG, AMOUNT_MISMATCH, FEE_MISMATCH }

data class PgLine(val transactionId: String, val orderId: String, val amount: Long, val fee: Long)

data class LedgerCardLine(val transactionId: String, val orderId: String, val amount: Long, val fee: Long)

data class Issue(
    val type: IssueType,
    val transactionId: String,
    val orderId: String?,
    val ledgerAmount: Long?,
    val pgAmount: Long?,
    val ledgerFee: Long?,
    val pgFee: Long?,
)

data class ReconciliationResult(val matched: Int, val issues: List<Issue>)

/**
 * PG 정산 파일 ↔ 우리 원장 대사. PG 거래번호로 1:1 매칭한다.
 *
 * - MISSING_IN_LEDGER : PG 에는 승인이 있는데 우리 기록이 없다 → 고객 돈은 나갔는데 주문이 없을 수 있음 (가장 위험)
 * - MISSING_IN_PG     : 우리는 매출로 잡았는데 PG 에 없다 → 매장에 과지급 위험
 * - AMOUNT_MISMATCH / FEE_MISMATCH : 금액·수수료 1원이라도 다르면 이슈
 */
object Reconciler {
    fun reconcile(pg: List<PgLine>, ledger: List<LedgerCardLine>): ReconciliationResult {
        val pgById = pg.associateBy { it.transactionId }
        val ledgerById = ledger.associateBy { it.transactionId }
        val issues = mutableListOf<Issue>()
        var matched = 0

        pg.forEach { p ->
            val l = ledgerById[p.transactionId]
            when {
                l == null -> issues += Issue(IssueType.MISSING_IN_LEDGER, p.transactionId, p.orderId, null, p.amount, null, p.fee)
                l.amount != p.amount -> issues += Issue(IssueType.AMOUNT_MISMATCH, p.transactionId, p.orderId, l.amount, p.amount, l.fee, p.fee)
                l.fee != p.fee -> issues += Issue(IssueType.FEE_MISMATCH, p.transactionId, p.orderId, l.amount, p.amount, l.fee, p.fee)
                else -> matched++
            }
        }
        ledger.filter { it.transactionId !in pgById }.forEach { l ->
            issues += Issue(IssueType.MISSING_IN_PG, l.transactionId, l.orderId, l.amount, null, l.fee, null)
        }
        return ReconciliationResult(matched, issues)
    }
}
