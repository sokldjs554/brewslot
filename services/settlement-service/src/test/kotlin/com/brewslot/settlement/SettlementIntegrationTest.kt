package com.brewslot.settlement

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.messaging.contract.PaymentRefunded
import com.brewslot.messaging.contract.PointsRedeemed
import com.brewslot.settlement.application.PgSettlementFileClient
import com.brewslot.settlement.application.SettlementIngestor
import com.brewslot.settlement.application.SettlementService
import com.brewslot.settlement.domain.IssueType
import com.brewslot.settlement.domain.PgLine
import com.brewslot.testsupport.Containers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(properties = ["spring.config.name=settlement-service"])
@Import(SettlementIntegrationTest.StubPg::class)
class SettlementIntegrationTest {
    @Autowired lateinit var ingestor: SettlementIngestor

    @Autowired lateinit var settlement: SettlementService

    @Autowired lateinit var codec: EnvelopeCodec

    @Autowired lateinit var jdbc: JdbcClient

    private fun ingest(payload: Any, eventId: UUID = UUID.randomUUID()) {
        ingestor.ingest(EventEnvelope(eventId, payload::class.java.simpleName, "x", Instant.now(), null, null, codec.mapper.valueToTree(payload)))
    }

    private fun kst(date: String, time: String) = Instant.parse("${date}T$time:00+09:00")

    @Test
    fun `마감 - 매장별 정산서, 중복 이벤트 무시, 멱등 재실행, 늦게 온 과거 거래는 다음 정산에 이월`() {
        val store = 9001L
        val o1 = UUID.randomUUID().toString()
        val o2 = UUID.randomUUID().toString()
        val captured = PaymentCaptured("p1", o1, 1, store, 1, 10_000, "pg-a1", kst("2026-10-21", "08:10"))
        val dupId = UUID.randomUUID()
        ingest(captured, dupId)
        ingest(captured, dupId)
        ingest(PointsRedeemed(o1, 1, 1, store, 2_000, kst("2026-10-21", "08:10")))
        ingest(PaymentCaptured("p2", o2, 2, store, 1, 5_500, "pg-a2", kst("2026-10-21", "23:59")))
        ingest(PaymentRefunded("p2", o2, store, 1, 5_500, "pg-c2", kst("2026-10-22", "00:01"))) // 자정 넘어 환불 → 다음 영업일

        val first = settlement.close(LocalDate.parse("2026-10-21")).single { it.draft.storeId == store }.draft
        assertThat(first.entryCount).isEqualTo(3)
        assertThat(first.netSales).isEqualTo(17_500)
        assertThat(first.pgFee).isEqualTo(220 + 121)
        assertThat(first.platformFee).isEqualTo(175)
        assertThat(first.payout).isEqualTo(17_500 - 341 - 175)

        assertThat(settlement.close(LocalDate.parse("2026-10-21")).filter { it.draft.storeId == store }).isEmpty()

        // 10/21 거래인데 마감 후에 도착한 이벤트
        ingest(PaymentCaptured("p3", UUID.randomUUID().toString(), 3, store, 1, 3_000, "pg-a3", kst("2026-10-21", "21:00")))
        val second = settlement.close(LocalDate.parse("2026-10-22")).single { it.draft.storeId == store }.draft
        assertThat(second.entryCount).isEqualTo(2)
        assertThat(second.carriedOverCount).isEqualTo(1)
        assertThat(second.netSales).isEqualTo(-5_500 + 3_000)

        assertThat(settlement.statements(store, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))).hasSize(2)
    }

    @Test
    fun `대사 - PG 파일과 원장을 거래번호로 맞춰 이슈를 기록한다`() {
        val o = UUID.randomUUID().toString()
        ingest(PaymentCaptured("p9", o, 1, 9002, 1, 10_000, "pg-r1", kst("2026-10-25", "10:00")))
        ingest(PaymentCaptured("p10", UUID.randomUUID().toString(), 1, 9002, 1, 4_000, "pg-r2", kst("2026-10-25", "10:05")))

        val view = settlement.reconcile(LocalDate.parse("2026-10-25"))

        assertThat(view.matchedCount).isEqualTo(1)
        assertThat(view.issues.map { it.type }).containsExactlyInAnyOrder(IssueType.MISSING_IN_PG, IssueType.MISSING_IN_LEDGER)
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_issue WHERE run_id = :id").param("id", view.runId).query(Int::class.java).single())
            .isEqualTo(2)
    }

    @Test
    fun `정산서의 지급액 = 순매출 - 수수료 가 DB 제약으로도 강제된다`() {
        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO settlement_statement (store_id, settlement_date, card_sales, card_refunds, point_sales, point_reversals,
                    net_sales, pg_fee, platform_fee, payout, entry_count, carried_over_count, created_at)
                VALUES (1, '2026-01-01', 0, 0, 0, 0, 1000, 22, 10, 999, 1, 0, now())
                """.trimIndent(),
            ).update()
        }.hasMessageContaining("ck_payout")
    }

    @TestConfiguration
    class StubPg {
        @Bean
        @Primary
        fun stubPgFiles() = object : PgSettlementFileClient {
            override fun fetch(date: LocalDate) = listOf(
                PgLine("pg-r1", "irrelevant", 10_000, 220),
                PgLine("pg-ghost", UUID.randomUUID().toString(), 7_000, 154),
            )
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Containers.registerPostgres(registry, "settlement_db")
            Containers.registerKafka(registry)
        }
    }
}
