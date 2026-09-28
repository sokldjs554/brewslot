package com.brewslot.settlement.application

import com.brewslot.settlement.domain.EntryKind
import com.brewslot.settlement.domain.LedgerCardLine
import com.brewslot.settlement.domain.PgLine
import com.brewslot.settlement.domain.Reconciler
import com.brewslot.settlement.domain.SettlementCalculator
import com.brewslot.settlement.domain.SettlementEntry
import com.brewslot.settlement.domain.StatementDraft
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate

data class StatementView(val id: Long, val draft: StatementDraft)

data class ReconciliationView(
    val runId: Long,
    val businessDate: LocalDate,
    val pgCount: Int,
    val ledgerCount: Int,
    val matchedCount: Int,
    val issues: List<com.brewslot.settlement.domain.Issue>,
)

/** PG 가 내려주는 정산 파일을 가져오는 경계. (여기서는 payment-service 의 Fake PG 엔드포인트) */
interface PgSettlementFileClient {
    fun fetch(date: LocalDate): List<PgLine>
}

@Component
class HttpPgSettlementFileClient(props: SettlementProperties) : PgSettlementFileClient {
    private val client = RestClient.builder().baseUrl(props.paymentServiceUrl).build()

    private data class Line(val transactionId: String, val orderId: String, val kind: String, val amount: Long, val fee: Long)

    override fun fetch(date: LocalDate): List<PgLine> =
        client.get().uri("/pg/settlement-files/{date}", date).retrieve().body<List<Line>>()!!
            .map { PgLine(it.transactionId, it.orderId, it.amount, it.fee) }
}

@Service
class SettlementService(
    private val jdbc: JdbcClient,
    private val props: SettlementProperties,
    private val pgFiles: PgSettlementFileClient,
    private val clock: Clock,
) {
    /**
     * 정산일 마감. 해당 일자까지 발생했지만 아직 정산서에 묶이지 않은 모든 건을 매장별 정산서로 만든다.
     * 마감 이후에 도착한 과거 거래(지연 이벤트)는 다음 마감의 정산서에 "이월분(carriedOver)" 으로 포함된다.
     * 같은 날짜로 다시 실행해도 이미 정산서가 있는 매장은 건너뛴다(멱등).
     */
    @Transactional
    fun close(settlementDate: LocalDate): List<StatementView> {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", 0x5E771E_0000L + settlementDate.toEpochDay()).query(Any::class.java).single()
        val stores = jdbc.sql(
            """
            SELECT DISTINCT e.store_id FROM settlement_entry e
            WHERE e.statement_id IS NULL AND e.business_date <= :date
              AND NOT EXISTS (SELECT 1 FROM settlement_statement s WHERE s.store_id = e.store_id AND s.settlement_date = :date)
            ORDER BY e.store_id
            """.trimIndent(),
        ).param("date", settlementDate).query(Long::class.java).list()

        return stores.map { storeId ->
            val entries = jdbc.sql(
                """
                SELECT id, store_id, kind, amount, pg_fee, business_date FROM settlement_entry
                WHERE store_id = :storeId AND statement_id IS NULL AND business_date <= :date
                ORDER BY id FOR UPDATE
                """.trimIndent(),
            ).param("storeId", storeId).param("date", settlementDate).query { rs, _ ->
                SettlementEntry(
                    rs.getLong("id"), rs.getLong("store_id"), EntryKind.valueOf(rs.getString("kind")), rs.getLong("amount"),
                    rs.getLong("pg_fee"), rs.getDate("business_date").toLocalDate(),
                )
            }.list()
            val draft = SettlementCalculator.draft(storeId, settlementDate, entries, props.platformFeeBps)
            val id = jdbc.sql(
                """
                INSERT INTO settlement_statement (store_id, settlement_date, card_sales, card_refunds, point_sales, point_reversals,
                    coupon_sales, coupon_reversals, net_sales, pg_fee, platform_fee, payout, entry_count, carried_over_count, created_at)
                VALUES (:storeId, :date, :cs, :cr, :ps, :pr, :cps, :cpr, :net, :pgFee, :platformFee, :payout, :cnt, :carried, :now)
                RETURNING id
                """.trimIndent(),
            )
                .param("storeId", storeId)
                .param("date", settlementDate)
                .param("cs", draft.cardSales)
                .param("cr", draft.cardRefunds)
                .param("ps", draft.pointSales)
                .param("pr", draft.pointReversals)
                .param("cps", draft.couponSales)
                .param("cpr", draft.couponReversals)
                .param("net", draft.netSales)
                .param("pgFee", draft.pgFee)
                .param("platformFee", draft.platformFee)
                .param("payout", draft.payout)
                .param("cnt", draft.entryCount)
                .param("carried", draft.carriedOverCount)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long::class.java)
                .single()
            jdbc.sql("UPDATE settlement_entry SET statement_id = :sid WHERE id IN (:ids)")
                .param("sid", id).param("ids", entries.map { it.id }).update()
            StatementView(id, draft)
        }
    }

    fun statements(storeId: Long, from: LocalDate, to: LocalDate): List<StatementView> = jdbc.sql(
        """
        SELECT * FROM settlement_statement
        WHERE store_id = :storeId AND settlement_date BETWEEN :from AND :to
        ORDER BY settlement_date DESC
        """.trimIndent(),
    ).param("storeId", storeId).param("from", from).param("to", to).query { rs, _ ->
        StatementView(
            rs.getLong("id"),
            StatementDraft(
                rs.getLong("store_id"), rs.getDate("settlement_date").toLocalDate(), rs.getLong("card_sales"), rs.getLong("card_refunds"),
                rs.getLong("point_sales"), rs.getLong("point_reversals"), rs.getLong("net_sales"), rs.getLong("pg_fee"),
                rs.getLong("platform_fee"), rs.getLong("payout"), rs.getInt("entry_count"), rs.getInt("carried_over_count"),
                rs.getLong("coupon_sales"), rs.getLong("coupon_reversals"),
            ),
        )
    }.list()

    @Transactional
    fun reconcile(date: LocalDate): ReconciliationView {
        val pg = pgFiles.fetch(date)
        val ledger = jdbc.sql(
            """
            SELECT pg_transaction_id, order_id::text AS order_id, amount, pg_fee FROM settlement_entry
            WHERE business_date = :date AND kind IN ('CARD_SALE', 'CARD_REFUND')
            """.trimIndent(),
        ).param("date", date).query { rs, _ ->
            LedgerCardLine(rs.getString("pg_transaction_id"), rs.getString("order_id"), rs.getLong("amount"), rs.getLong("pg_fee"))
        }.list()

        val result = Reconciler.reconcile(pg, ledger)
        val runId = jdbc.sql(
            """
            INSERT INTO reconciliation_run (business_date, pg_count, ledger_count, matched_count, issue_count, created_at)
            VALUES (:date, :pg, :ledger, :matched, :issues, :now) RETURNING id
            """.trimIndent(),
        )
            .param("date", date)
            .param("pg", pg.size)
            .param("ledger", ledger.size)
            .param("matched", result.matched)
            .param("issues", result.issues.size)
            .param("now", Timestamp.from(clock.instant()))
            .query(Long::class.java)
            .single()
        result.issues.forEach { i ->
            jdbc.sql(
                """
                INSERT INTO reconciliation_issue (run_id, issue_type, pg_transaction_id, order_id, ledger_amount, pg_amount, ledger_fee, pg_fee)
                VALUES (:run, :type, :tx, CAST(:orderId AS uuid), :la, :pa, :lf, :pf)
                """.trimIndent(),
            )
                .param("run", runId)
                .param("type", i.type.name)
                .param("tx", i.transactionId)
                .param("orderId", i.orderId)
                .param("la", i.ledgerAmount)
                .param("pa", i.pgAmount)
                .param("lf", i.ledgerFee)
                .param("pf", i.pgFee)
                .update()
        }
        return ReconciliationView(runId, date, pg.size, ledger.size, result.matched, result.issues)
    }
}
