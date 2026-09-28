package com.brewslot.loyalty

import com.brewslot.loyalty.application.CouponService
import com.brewslot.loyalty.application.PointService
import com.brewslot.testsupport.Containers
import com.brewslot.testsupport.MutableClock
import com.brewslot.web.ConflictException
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(properties = ["spring.config.name=loyalty-service"])
@Import(CouponIntegrationTest.ClockConfig::class)
class CouponIntegrationTest {
    @Autowired lateinit var coupons: CouponService

    @Autowired lateinit var points: PointService

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var clock: MutableClock

    private val memberSeq = AtomicLong(2_000_000 + System.nanoTime() % 1_000_000)

    private fun campaign(limit: Int, discount: Long = 1_000, minOrder: Long = 0, validDays: Int = 14) =
        coupons.createCampaign(1, "테스트 이벤트", discount, minOrder, limit, validDays, null, clock.instant().plus(Duration.ofDays(30)))

    private fun events(orderId: UUID) = jdbc.sql("SELECT event_type FROM outbox_event WHERE message_key = :k ORDER BY id")
        .param("k", orderId.toString()).query(String::class.java).list()

    private fun lastFailureReason(orderId: UUID) = jdbc.sql(
        "SELECT envelope::text FROM outbox_event WHERE message_key = :k AND event_type = 'PointsRedemptionFailed' ORDER BY id DESC LIMIT 1",
    ).param("k", orderId.toString()).query(String::class.java).single()

    private fun status(couponId: UUID) = jdbc.sql("SELECT status FROM member_coupon WHERE id = :id")
        .param("id", couponId).query(String::class.java).single()

    private fun balance(m: Long) = jdbc.sql("SELECT balance FROM point_wallet WHERE member_id = :m AND brand_id = 1")
        .param("m", m).query(Long::class.java).single()

    /** 원장 전체에서 계정별 차변·대변이 맞는지: 거래마다 균형(트리거) + 브랜드 프로모션 비용 = 사용 − 사용취소 */
    private fun promotionCost() = jdbc.sql(
        "SELECT COALESCE(SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END), 0) FROM ledger_entry WHERE account = 'brand:1:promotion'",
    ).query(Long::class.java).single()

    @Test
    fun `선착순 10장 이벤트에 50명이 동시에 몰려도 정확히 10장만 발급된다`() {
        val c = campaign(limit = 10)
        val pool = Executors.newFixedThreadPool(16)
        val results = (1..50).map { memberSeq.incrementAndGet() }.map { m ->
            pool.submit(
                Callable {
                    try {
                        coupons.claim(c.id, m).newlyIssued
                    } catch (e: ConflictException) {
                        false
                    }
                },
            )
        }.map { it.get() }
        pool.shutdown()

        assertThat(results.count { it }).isEqualTo(10)
        val (issued, rows) = jdbc.sql(
            "SELECT c.issued_count, (SELECT COUNT(*) FROM member_coupon m WHERE m.campaign_id = c.id) FROM coupon_campaign c WHERE c.id = :id",
        ).param("id", c.id).query { rs, _ -> rs.getInt(1) to rs.getInt(2) }.single()
        assertThat(issued).isEqualTo(10)
        assertThat(rows).isEqualTo(10) // 소진으로 실패한 요청은 쿠폰 행도 롤백됐다
    }

    @Test
    fun `같은 회원이 다시 받으면 새로 발급하지 않고 같은 쿠폰을 돌려준다`() {
        val c = campaign(limit = 5)
        val m = memberSeq.incrementAndGet()
        val first = coupons.claim(c.id, m)
        val again = coupons.claim(c.id, m)
        assertThat(first.newlyIssued).isTrue()
        assertThat(again.newlyIssued).isFalse()
        assertThat(again.coupon.id).isEqualTo(first.coupon.id)
    }

    @Test
    fun `쿠폰과 포인트를 한 트랜잭션으로 쓰고, 취소하면 둘 다 되돌린다`() {
        val c = campaign(limit = 5, minOrder = 5_000)
        val m = memberSeq.incrementAndGet()
        points.grant(m, 1, 2_000, 30, "웰컴")
        val coupon = coupons.claim(c.id, m).coupon
        val costBefore = promotionCost()

        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 1_500, coupon.id, 1_000, 8_500)
        assertThat(status(coupon.id)).isEqualTo("USED")
        assertThat(balance(m)).isEqualTo(500)
        assertThat(promotionCost() - costBefore).isEqualTo(1_000) // 할인은 브랜드 비용
        assertThat(events(orderId)).containsExactly("PointsRedeemed")

        points.reverseRedemption(orderId, "PAYMENT_DECLINED")
        points.reverseRedemption(orderId, "PAYMENT_DECLINED") // 중복 보상
        assertThat(status(coupon.id)).isEqualTo("ISSUED")
        assertThat(balance(m)).isEqualTo(2_000)
        assertThat(promotionCost()).isEqualTo(costBefore)
        assertThat(events(orderId)).containsExactly("PointsRedeemed", "PointsRedemptionReversed")
    }

    @Test
    fun `쿠폰은 통과해도 포인트가 부족하면 아무것도 쓰지 않는다`() {
        val c = campaign(limit = 5)
        val m = memberSeq.incrementAndGet()
        points.grant(m, 1, 500, 30, "웰컴")
        val coupon = coupons.claim(c.id, m).coupon

        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 2_000, coupon.id, 1_000, 8_000)
        assertThat(events(orderId)).containsExactly("PointsRedemptionFailed")
        assertThat(lastFailureReason(orderId)).contains("INSUFFICIENT_BALANCE")
        assertThat(status(coupon.id)).isEqualTo("ISSUED")
        assertThat(balance(m)).isEqualTo(500)
    }

    @Test
    fun `할인액을 부풀린 요청과 이미 쓴 쿠폰은 거절된다`() {
        val c = campaign(limit = 5)
        val m = memberSeq.incrementAndGet()
        val coupon = coupons.claim(c.id, m).coupon

        val tampered = UUID.randomUUID()
        points.redeem(tampered, m, 1, 101, 0, coupon.id, 3_000, 8_000)
        assertThat(lastFailureReason(tampered)).contains("COUPON_AMOUNT_MISMATCH")

        points.redeem(UUID.randomUUID(), m, 1, 101, 0, coupon.id, 1_000, 8_000)
        val second = UUID.randomUUID()
        points.redeem(second, m, 1, 101, 0, coupon.id, 1_000, 8_000)
        assertThat(lastFailureReason(second)).contains("COUPON_ALREADY_USED")
    }

    @Test
    fun `같은 쿠폰으로 두 주문이 동시에 결제해도 한 주문만 쓴다`() {
        val c = campaign(limit = 5)
        val m = memberSeq.incrementAndGet()
        val coupon = coupons.claim(c.id, m).coupon
        val orders = listOf(UUID.randomUUID(), UUID.randomUUID())
        val pool = Executors.newFixedThreadPool(2)
        orders.map { o -> pool.submit { points.redeem(o, m, 1, 101, 0, coupon.id, 1_000, 8_000) } }.forEach { it.get() }
        pool.shutdown()

        val outcomes = orders.map { events(it).single() }
        assertThat(outcomes).containsExactlyInAnyOrder("PointsRedeemed", "PointsRedemptionFailed")
    }

    @Test
    fun `결제 중 유효기간이 지난 쿠폰은 취소돼도 되살리지 않고 만료로 남는다`() {
        val c = campaign(limit = 5, validDays = 1)
        val m = memberSeq.incrementAndGet()
        val coupon = coupons.claim(c.id, m).coupon
        val orderId = UUID.randomUUID()
        points.redeem(orderId, m, 1, 101, 0, coupon.id, 1_000, 8_000)

        clock.advance(Duration.ofDays(2))
        try {
            points.reverseRedemption(orderId, "CUSTOMER_CANCELLED")
            assertThat(status(coupon.id)).isEqualTo("EXPIRED")
        } finally {
            clock.advance(Duration.ofDays(-2))
        }
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        @Primary
        fun clock() = MutableClock(Instant.parse("2026-10-01T00:00:00Z"))
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
