package com.brewslot.loyalty

import com.brewslot.loyalty.application.PointExpiryJob
import com.brewslot.loyalty.application.PointService
import com.brewslot.testsupport.Containers
import com.brewslot.testsupport.MutableClock
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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(properties = ["spring.config.name=loyalty-service"])
@Import(PointServiceIntegrationTest.ClockConfig::class)
class PointServiceIntegrationTest {
    @Autowired lateinit var points: PointService

    @Autowired lateinit var expiryJob: PointExpiryJob

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var clock: MutableClock

    @Autowired lateinit var tx: TransactionTemplate

    private val memberSeq = AtomicLong(System.nanoTime() % 1_000_000)

    private fun events(orderId: UUID) =
        jdbc.sql(
            "SELECT event_type FROM outbox_event WHERE message_key = :k ORDER BY id",
        ).param("k", orderId.toString()).query(String::class.java).list()

    private fun balance(m: Long, b: Long = 1) =
        jdbc.sql(
            "SELECT balance FROM point_wallet WHERE member_id = :m AND brand_id = :b",
        ).param("m", m).param("b", b).query(Long::class.java).single()

    /** 세 가지 방법으로 구한 잔액이 모두 같아야 한다: 지갑 캐시 = lot 잔여 합 = 원장(대변 - 차변) */
    private fun assertInvariant(m: Long, b: Long = 1) {
        val lots = jdbc.sql("SELECT COALESCE(SUM(remaining), 0) FROM point_lot WHERE member_id = :m AND brand_id = :b")
            .param("m", m).param("b", b).query(Long::class.java).single()
        val ledger = jdbc.sql(
            "SELECT COALESCE(SUM(CASE direction WHEN 'C' THEN amount ELSE -amount END), 0) FROM ledger_entry WHERE account = :a",
        ).param("a", "member:$m:brand:$b").query(Long::class.java).single()
        assertThat(balance(m, b)).isEqualTo(lots).isEqualTo(ledger)
    }

    @Test
    fun `적립 → 사용 → 사용취소 흐름에서 잔액 불변식이 유지된다`() {
        val m = memberSeq.incrementAndGet()
        points.earn(UUID.randomUUID(), m, 1, 100_000) // 3% → 3,000
        points.grant(m, 1, 2_000, 30, "웰컴")
        assertThat(balance(m)).isEqualTo(5_000)

        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 4_000)
        points.redeem(orderId, m, 1, 101, 4_000) // 중복 명령
        assertThat(balance(m)).isEqualTo(1_000)
        assertThat(events(orderId)).containsExactly("PointsRedeemed")
        assertInvariant(m)

        points.reverseRedemption(orderId, "PAYMENT_DECLINED")
        points.reverseRedemption(orderId, "PAYMENT_DECLINED")
        assertThat(balance(m)).isEqualTo(5_000)
        assertThat(events(orderId)).containsExactly("PointsRedeemed", "PointsRedemptionReversed")
        assertInvariant(m)
    }

    private fun clearing(storeId: Long, orderId: UUID) = jdbc.sql(
        """
        SELECT COALESCE(SUM(CASE e.direction WHEN 'C' THEN e.amount ELSE -e.amount END), 0)
        FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
        WHERE e.account = :a AND t.order_id = :o
        """.trimIndent(),
    ).param("a", "store:$storeId:clearing").param("o", orderId).query(Long::class.java).single()

    @Test
    fun `매장 변경 - 매장 채권이 새 매장으로 옮겨지고, 옮긴 뒤 사용 취소는 새 매장에서 빠진다 (도착 순서 무관)`() {
        for (reversalFirst in listOf(false, true)) {
            val m = memberSeq.incrementAndGet()
            points.grant(m, 1, 3_000, 30, "웰컴")
            val orderId = UUID.randomUUID()
            points.redeem(orderId, m, 1, 101, 2_000)
            assertThat(clearing(101, orderId)).isEqualTo(2_000)

            // 매장 변경 이벤트와 (새 매장 기준) 사용 취소 명령은 다른 토픽이라 어느 쪽이 먼저 올지 모른다
            val transfer = { points.transferClearing(orderId, m, 1, 101, 103, 2_000) }
            val reverse = { points.reverseRedemption(orderId, "CUSTOMER_CANCELLED", 103) }
            if (reversalFirst) {
                reverse()
                transfer()
            } else {
                transfer()
                assertThat(clearing(101, orderId)).isZero()
                assertThat(clearing(103, orderId)).isEqualTo(2_000)
                reverse()
            }
            transfer() // 중복 이벤트
            assertThat(clearing(101, orderId)).isZero()
            assertThat(clearing(103, orderId)).isZero()
            assertThat(balance(m)).isEqualTo(3_000)
            assertInvariant(m)
        }
    }

    @Test
    fun `소멸이 임박한 포인트부터 쓰고, 사용취소 시 원래 lot 으로 돌아간다`() {
        val m = memberSeq.incrementAndGet()
        points.grant(m, 1, 1_000, 365, "장기")
        points.grant(m, 1, 1_000, 10, "단기")

        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 1_500)
        val remaining = jdbc.sql(
            "SELECT memo, remaining FROM point_lot l JOIN ledger_transaction t ON t.id = l.source_tx_id WHERE l.member_id = :m",
        )
            .param("m", m).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        assertThat(remaining).isEqualTo(mapOf("장기" to 500L, "단기" to 0L))

        points.reverseRedemption(orderId, "CANCEL")
        val restored = jdbc.sql(
            "SELECT remaining FROM point_lot WHERE member_id = :m ORDER BY id",
        ).param("m", m).query(Long::class.java).list()
        assertThat(restored).containsExactly(1_000L, 1_000L)
    }

    @Test
    fun `잔액보다 많이 쓰려 하면 실패 이벤트로 응답하고 원장은 변하지 않는다`() {
        val m = memberSeq.incrementAndGet()
        points.grant(m, 1, 1_000, 30, "지급")
        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 1_500)
        assertThat(events(orderId)).containsExactly("PointsRedemptionFailed")
        assertThat(balance(m)).isEqualTo(1_000)
        assertInvariant(m)
    }

    @Test
    fun `같은 지갑에 동시 사용 요청 20건 - 잔액을 넘는 사용은 한 건도 없다`() {
        val m = memberSeq.incrementAndGet()
        points.grant(m, 1, 5_000, 30, "지급")
        val pool = Executors.newFixedThreadPool(20)
        val orders = List(20) { UUID.randomUUID() }
        pool.invokeAll(orders.map { id -> Callable { points.redeem(id, m, 1, 101, 1_000) } }).forEach { it.get() }
        pool.shutdown()

        val redeemed = orders.count { events(it) == listOf("PointsRedeemed") }
        assertThat(redeemed).isEqualTo(5)
        assertThat(balance(m)).isEqualTo(0)
        assertInvariant(m)
    }

    @Test
    fun `유효기간이 지난 포인트는 소멸 처리되어 브랜드의 소멸수익으로 넘어간다`() {
        val m = memberSeq.incrementAndGet()
        points.grant(m, 2, 700, 1, "단기")
        points.grant(m, 2, 300, 365, "장기")
        clock.advance(Duration.ofDays(2))
        expiryJob.run()

        assertThat(balance(m, 2)).isEqualTo(300)
        assertInvariant(m, 2)
        clock.advance(Duration.ofDays(-2))
    }

    @Test
    fun `DB 트리거가 차변·대변이 맞지 않는 분개의 커밋을 거부한다`() {
        assertThatThrownBy {
            tx.executeWithoutResult {
                val id = jdbc.sql(
                    "INSERT INTO ledger_transaction (tx_type, member_id, brand_id, amount, created_at) VALUES ('GRANT', 1, 1, 100, now()) RETURNING id",
                ).query(Long::class.java).single()
                jdbc.sql(
                    "INSERT INTO ledger_entry (transaction_id, account, direction, amount) VALUES (:id, 'x', 'D', 100)",
                ).param("id", id).update()
                jdbc.sql(
                    "INSERT INTO ledger_entry (transaction_id, account, direction, amount) VALUES (:id, 'y', 'C', 90)",
                ).param("id", id).update()
            }
        }.rootCause().hasMessageContaining("unbalanced")
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        @Primary
        fun clock() = MutableClock(Instant.parse("2026-10-01T00:00:00Z"))

        @Bean
        fun txTemplate(tm: org.springframework.transaction.PlatformTransactionManager) = TransactionTemplate(tm)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Containers.registerPostgres(registry, "loyalty_db")
            Containers.registerKafka(registry)
        }
    }
}
