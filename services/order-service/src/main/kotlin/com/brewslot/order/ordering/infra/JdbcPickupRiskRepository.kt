package com.brewslot.order.ordering.infra

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class PickupRisk(
    val orderId: UUID,
    val storeId: Long,
    val delayMinutes: Int,
    val suggestedStoreId: Long?,
    val suggestedPickupAt: Instant?,
    val walkMinutes: Int?,
    val detectedAt: Instant,
)

@Repository
class JdbcPickupRiskRepository(private val jdbc: JdbcClient) {
    companion object {
        /** 이보다 오래된 미완료 주문은 매장 대기열이 아니라 상태 처리를 잊은 주문으로 보고 지연 계산에서 뺀다 */
        private val LOOKBACK: java.time.Duration = java.time.Duration.ofMinutes(90)
    }

    /**
     * 밀린 매장과 "가장 오래 밀린 주문이 준비됐어야 할 시각". 결제됐지만 READY 가 아닌 주문만 본다(부분 인덱스).
     * 준비됐어야 할 시각 = 그 주문의 마지막 제조 슬롯 시작 + 슬롯 길이.
     */
    fun lateStores(now: Instant): Map<Long, Instant> = jdbc.sql(
        """
        SELECT o.store_id, MIN(x.ready_by) AS overdue_since
        FROM orders o
        JOIN store s ON s.id = o.store_id
        CROSS JOIN LATERAL (
            SELECT MAX(l.slot_start) + s.slot_minutes * INTERVAL '1 minute' AS ready_by
            FROM slot_reservation_line l WHERE l.order_id = o.id
        ) x
        WHERE o.status IN ('PAID', 'PREPARING') AND o.promised_pickup_at >= :since AND x.ready_by < :now
        GROUP BY o.store_id
        """.trimIndent(),
    ).param("now", Timestamp.from(now)).param("since", Timestamp.from(now.minus(LOOKBACK))).query { rs, _ ->
        rs.getLong("store_id") to
            rs.getTimestamp("overdue_since").toInstant()
    }
        .list().toMap()

    /** 아직 알리지 않은, 곧 받을 결제 완료 주문 */
    fun unalertedOrders(storeId: Long, from: Instant, until: Instant, limit: Int): List<UUID> = jdbc.sql(
        """
        SELECT o.id FROM orders o
        WHERE o.store_id = :storeId AND o.status = 'PAID' AND o.promised_pickup_at > :from AND o.promised_pickup_at <= :until
          AND NOT EXISTS (SELECT 1 FROM pickup_risk r WHERE r.order_id = o.id)
        ORDER BY o.promised_pickup_at
        LIMIT :limit
        """.trimIndent(),
    )
        .param("storeId", storeId)
        .param("from", Timestamp.from(from))
        .param("until", Timestamp.from(until))
        .param("limit", limit)
        .query(UUID::class.java)
        .list()

    /** 주문당 한 번. 이미 있으면 false (여러 인스턴스가 동시에 감지해도 한 번만 알린다) */
    fun insert(r: PickupRisk): Boolean = jdbc.sql(
        """
        INSERT INTO pickup_risk (order_id, store_id, delay_minutes, suggested_store_id, suggested_pickup_at, walk_minutes, detected_at)
        VALUES (:orderId, :storeId, :delay, :sStore, :sAt, :walk, :at)
        ON CONFLICT (order_id) DO NOTHING
        """.trimIndent(),
    )
        .param("orderId", r.orderId)
        .param("storeId", r.storeId)
        .param("delay", r.delayMinutes)
        .param("sStore", r.suggestedStoreId)
        .param("sAt", r.suggestedPickupAt?.let(Timestamp::from))
        .param("walk", r.walkMinutes)
        .param("at", Timestamp.from(r.detectedAt))
        .update() == 1

    fun find(orderId: UUID): PickupRisk? = jdbc.sql("SELECT * FROM pickup_risk WHERE order_id = :id")
        .param("id", orderId)
        .query { rs, _ ->
            PickupRisk(
                rs.getObject("order_id", UUID::class.java),
                rs.getLong("store_id"),
                rs.getInt("delay_minutes"),
                rs.getObject("suggested_store_id") as Long?,
                rs.getTimestamp("suggested_pickup_at")?.toInstant(),
                rs.getObject("walk_minutes") as Int?,
                rs.getTimestamp("detected_at").toInstant(),
            )
        }.optional().orElse(null)
}
