package com.brewslot.order.performance

import com.brewslot.order.OrderIntegrationTest
import com.brewslot.testsupport.QueryPlan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import javax.sql.DataSource

/**
 * 실행계획 회귀 테스트.
 *
 * 운영 규모를 흉내 낸 데이터(주문 조회모델 20만 건, 주문 20만 건)를 넣고 ANALYZE 로 통계를 갱신한 뒤
 * 핵심 쿼리의 계획이 의도한 인덱스를 타는지 검증한다. 인덱스를 지우거나 쿼리를 바꿔 계획이 퇴화하면 빌드가 깨진다.
 * 측정 수치와 before/after 비교는 docs/performance/query-plans.md 참고.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanRegressionTest : OrderIntegrationTest() {
    @Autowired lateinit var dataSource: DataSource

    @BeforeAll
    fun seed() {
        val seeded = jdbc.sql("SELECT count(*) FROM member_order_view WHERE member_id = $HEAVY_MEMBER").query(Long::class.java).single()
        if (seeded > 0) return
        // 회원 1만 명 × 평균 20건
        jdbc.sql(
            """
            INSERT INTO member_order_view (order_id, member_id, store_id, store_name, status, total_amount, promised_pickup_at,
                                           items, cart_signature, placed_at, updated_at)
            SELECT gen_random_uuid(), 9000000 + (g % 10000), 101, '블루아워커피 역삼점', 'PICKED_UP', 4500,
                   timestamptz '2026-01-01' + (g || ' minutes')::interval, '[]'::jsonb, md5((g % 37)::text),
                   timestamptz '2026-01-01' + (g || ' minutes')::interval, now()
            FROM generate_series(1, 200000) g
            """.trimIndent(),
        ).update()
        // 헤비 유저(매일 여러 잔 주문하는 회원·법인 계정): 3만 건
        jdbc.sql(
            """
            INSERT INTO member_order_view (order_id, member_id, store_id, store_name, status, total_amount, promised_pickup_at,
                                           items, cart_signature, placed_at, updated_at)
            SELECT gen_random_uuid(), $HEAVY_MEMBER, 101, '블루아워커피 역삼점', 'PICKED_UP', 4500,
                   timestamptz '2025-01-01' + (g || ' minutes')::interval, '[]'::jsonb, md5((g % 37)::text),
                   timestamptz '2025-01-01' + (g || ' minutes')::interval, now()
            FROM generate_series(1, 30000) g
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO orders (id, member_id, store_id, brand_id, status, promised_pickup_at, total_amount, point_amount,
                                hold_expires_at, idempotency_key, request_hash, created_at)
            SELECT gen_random_uuid(), 9000000 + g, 101, 1,
                   CASE WHEN g % 1000 = 0 THEN 'PENDING_PAYMENT' ELSE 'PICKED_UP' END,
                   now(), 4500, 0, timestamptz '2026-01-01' + (g || ' seconds')::interval, 'seed-' || g, 'x', now()
            FROM generate_series(1, 200000) g
            """.trimIndent(),
        ).update()
        jdbc.sql("ANALYZE member_order_view").update()
        jdbc.sql("ANALYZE orders").update()
    }

    /*
     * 참고: 주문이 20건뿐인 회원은 플래너가 Bitmap Scan + Sort(20행) 를 고르며, 이는 합리적인 선택이다.
     * 인덱스 순서 스캔이 "반드시" 필요한 것은 주문이 수만 건인 헤비 유저 — 여기서 Sort 가 생기면 전 행을 읽고 정렬한다.
     */
    @Test
    fun `주문내역 첫 페이지 - 헤비 유저도 복합 인덱스 순서대로 21행만 읽고 정렬 노드가 없다`() {
        val plan = QueryPlan.explain(
            dataSource,
            "member-orders-first-page",
            """
            SELECT order_id, placed_at FROM member_order_view
            WHERE member_id = :memberId
            ORDER BY placed_at DESC, order_id DESC
            LIMIT 21
            """.trimIndent(),
            mapOf("memberId" to HEAVY_MEMBER),
        )
        assertThat(plan.usedIndexes()).contains("idx_mov_member_placed")
        assertThat(plan.hasSortNode()).isFalse()
        assertThat(plan.nodes().first { it["Node Type"].asText().startsWith("Index") }["Actual Rows"].asLong()).isLessThanOrEqualTo(21)
        assertThat(plan.seqScannedRelations()).doesNotContain("member_order_view")
    }

    @Test
    fun `주문내역 커서 페이지 - 행 비교(row value) 조건도 인덱스 범위 스캔으로 처리된다`() {
        val plan = QueryPlan.explain(
            dataSource,
            "member-orders-cursor-page",
            """
            SELECT order_id, placed_at FROM member_order_view
            WHERE member_id = :memberId AND (placed_at, order_id) < (:placedAt, :orderId)
            ORDER BY placed_at DESC, order_id DESC
            LIMIT 21
            """.trimIndent(),
            mapOf(
                "memberId" to HEAVY_MEMBER,
                "placedAt" to java.sql.Timestamp.from(Instant.parse("2025-01-10T00:00:00Z")),
                "orderId" to java.util.UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"),
            ),
        )
        assertThat(plan.usedIndexes()).contains("idx_mov_member_placed")
        assertThat(plan.hasSortNode()).isFalse()
    }

    @Test
    fun `점유 만료 스캔 - 부분 인덱스로 PENDING_PAYMENT 행만 읽는다`() {
        val plan = QueryPlan.explain(
            dataSource,
            "orders-hold-expiry-scan",
            """
            SELECT id FROM orders
            WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= :now
            ORDER BY hold_expires_at
            LIMIT 100
            """.trimIndent(),
            mapOf("now" to java.sql.Timestamp.from(Instant.parse("2026-06-01T00:00:00Z"))),
        )
        assertThat(plan.usedIndexes()).contains("idx_orders_hold_expiry")
        assertThat(plan.seqScannedRelations()).doesNotContain("orders")
    }

    @Test
    fun `아웃박스 폴링 - 미발행 부분 인덱스를 사용한다`() {
        jdbc.sql(
            """
            INSERT INTO outbox_event (event_id, topic, message_key, event_type, envelope, created_at, published_at)
            SELECT gen_random_uuid(), 'order.events', 'k', 'Seed', '{}'::jsonb, now(), now() FROM generate_series(1, 50000)
            """.trimIndent(),
        ).update()
        jdbc.sql("ANALYZE outbox_event").update()
        val plan = QueryPlan.explain(
            dataSource,
            "outbox-unpublished-poll",
            "SELECT id FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 200",
        )
        assertThat(plan.usedIndexes()).contains("idx_outbox_unpublished")
        assertThat(plan.seqScannedRelations()).doesNotContain("outbox_event")
    }

    companion object {
        const val HEAVY_MEMBER = 8_888_888L
    }
}
