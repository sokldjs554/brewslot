package com.brewslot.order.ordering.infra

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.ordering.domain.CancelReason
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.domain.OrderStatus
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcOrderRepository(private val jdbc: JdbcClient) {
    fun insert(order: Order, idempotencyKey: String, requestHash: String) {
        jdbc.sql(
            """
            INSERT INTO orders (id, member_id, store_id, brand_id, status, promised_pickup_at, total_amount, point_amount,
                                coupon_id, coupon_amount, hold_expires_at, idempotency_key, request_hash, created_at, version)
            VALUES (:id, :memberId, :storeId, :brandId, :status, :pickupAt, :total, :points,
                    :couponId, :couponAmount, :holdExpiresAt, :idempotencyKey, :requestHash, :createdAt, 0)
            """.trimIndent(),
        )
            .param("id", order.id)
            .param("memberId", order.memberId)
            .param("storeId", order.storeId)
            .param("brandId", order.brandId)
            .param("status", order.status.name)
            .param("pickupAt", ts(order.promisedPickupAt))
            .param("total", order.totalAmount.won)
            .param("points", order.pointAmount.won)
            .param("couponId", order.couponId)
            .param("couponAmount", order.couponAmount.won)
            .param("holdExpiresAt", ts(order.holdExpiresAt))
            .param("idempotencyKey", idempotencyKey)
            .param("requestHash", requestHash)
            .param("createdAt", ts(order.createdAt))
            .update()
        insertLines(order)
    }

    /** 매장 변경: 주문 항목을 새 매장 메뉴로 교체한다 (금액은 같음 — [Order.transferTo] 가 검증). */
    fun replaceLines(order: Order) {
        jdbc.sql("DELETE FROM order_line WHERE order_id = :id").param("id", order.id).update()
        insertLines(order)
    }

    private fun insertLines(order: Order) {
        order.lines.forEach { line ->
            jdbc.sql(
                """
                INSERT INTO order_line (order_id, line_no, menu_item_id, name, unit_price, quantity, station, load_units, freshness_minutes)
                VALUES (:orderId, :lineNo, :menuItemId, :name, :unitPrice, :quantity, :station, :loadUnits, :freshness)
                """.trimIndent(),
            )
                .param("orderId", order.id)
                .param("lineNo", line.lineNo)
                .param("menuItemId", line.menuItemId)
                .param("name", line.name)
                .param("unitPrice", line.unitPrice.won)
                .param("quantity", line.quantity)
                .param("station", line.station.name)
                .param("loadUnits", line.loadUnits)
                .param("freshness", line.freshnessMinutes)
                .update()
        }
    }

    /** 낙관적 잠금: 읽은 이후 다른 트랜잭션이 바꿨다면 실패시킨다. */
    fun update(order: Order) {
        val updated = jdbc.sql(
            """
            UPDATE orders SET status = :status, paid_at = :paidAt, preparing_at = :preparingAt, ready_at = :readyAt,
                              picked_up_at = :pickedUpAt, cancelled_at = :cancelledAt, cancel_reason = :cancelReason,
                              store_id = :storeId, promised_pickup_at = :pickupAt,
                              version = version + 1
            WHERE id = :id AND version = :version
            """.trimIndent(),
        )
            .param("status", order.status.name)
            .param("paidAt", order.paidAt?.let(::ts))
            .param("preparingAt", order.preparingAt?.let(::ts))
            .param("readyAt", order.readyAt?.let(::ts))
            .param("pickedUpAt", order.pickedUpAt?.let(::ts))
            .param("cancelledAt", order.cancelledAt?.let(::ts))
            .param("cancelReason", order.cancelReason?.name)
            .param("storeId", order.storeId)
            .param("pickupAt", ts(order.promisedPickupAt))
            .param("id", order.id)
            .param("version", order.version)
            .update()
        if (updated != 1) throw OptimisticLockingFailureException("order ${order.id} was modified concurrently")
        order.version = order.version + 1
    }

    fun findById(id: UUID): Order? = findOne("WHERE id = :id", mapOf("id" to id), lock = false)

    /** Saga 응답 처리처럼 같은 주문에 대한 동시 처리가 예상되는 곳에서는 비관적 잠금으로 직렬화한다. */
    fun findByIdForUpdate(id: UUID): Order? = findOne("WHERE id = :id", mapOf("id" to id), lock = true)

    fun findByIdempotencyKey(memberId: Long, key: String): Pair<Order, String>? {
        val hash = jdbc.sql("SELECT id, request_hash FROM orders WHERE member_id = :memberId AND idempotency_key = :key")
            .param("memberId", memberId)
            .param("key", key)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("request_hash") }
            .optional()
            .orElse(null) ?: return null
        return findById(hash.first)!! to hash.second
    }

    /** 만료 대상 조회. SKIP LOCKED 로 여러 인스턴스가 같은 주문을 동시에 만료시키지 않는다. */
    fun lockExpiredHolds(now: Instant, limit: Int): List<UUID> = jdbc.sql(
        """
        SELECT id FROM orders
        WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= :now
        ORDER BY hold_expires_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
    ).param("now", ts(now)).param("limit", limit).query(UUID::class.java).list()

    private fun findOne(where: String, params: Map<String, Any>, lock: Boolean): Order? {
        val header = jdbc.sql(
            """
            SELECT id, member_id, store_id, brand_id, status, promised_pickup_at, point_amount, coupon_id, coupon_amount, hold_expires_at, created_at,
                   paid_at, preparing_at, ready_at, picked_up_at, cancelled_at, cancel_reason, version
            FROM orders $where ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
        ).params(params).query { rs, _ -> rs.toHeader() }.optional().orElse(null) ?: return null

        val lines = jdbc.sql(
            """
            SELECT line_no, menu_item_id, name, unit_price, quantity, station, load_units, freshness_minutes
            FROM order_line WHERE order_id = :id ORDER BY line_no
            """.trimIndent(),
        ).param("id", header.id).query { rs, _ ->
            OrderLine(
                lineNo = rs.getInt("line_no"),
                menuItemId = rs.getLong("menu_item_id"),
                name = rs.getString("name"),
                unitPrice = Money(rs.getLong("unit_price")),
                quantity = rs.getInt("quantity"),
                station = Station.valueOf(rs.getString("station")),
                loadUnits = rs.getInt("load_units"),
                freshnessMinutes = rs.getInt("freshness_minutes"),
            )
        }.list()

        return Order.reconstitute(
            id = header.id,
            memberId = header.memberId,
            storeId = header.storeId,
            brandId = header.brandId,
            lines = lines,
            promisedPickupAt = header.promisedPickupAt,
            pointAmount = Money(header.pointAmount),
            holdExpiresAt = header.holdExpiresAt,
            createdAt = header.createdAt,
            status = header.status,
            paidAt = header.paidAt,
            preparingAt = header.preparingAt,
            readyAt = header.readyAt,
            pickedUpAt = header.pickedUpAt,
            cancelledAt = header.cancelledAt,
            cancelReason = header.cancelReason,
            version = header.version,
            couponId = header.couponId,
            couponAmount = Money(header.couponAmount),
        )
    }

    private fun ResultSet.toHeader() = Header(
        id = getObject("id", UUID::class.java),
        memberId = getLong("member_id"),
        storeId = getLong("store_id"),
        brandId = getLong("brand_id"),
        status = OrderStatus.valueOf(getString("status")),
        promisedPickupAt = getTimestamp("promised_pickup_at").toInstant(),
        pointAmount = getLong("point_amount"),
        holdExpiresAt = getTimestamp("hold_expires_at").toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        paidAt = getTimestamp("paid_at")?.toInstant(),
        preparingAt = getTimestamp("preparing_at")?.toInstant(),
        readyAt = getTimestamp("ready_at")?.toInstant(),
        pickedUpAt = getTimestamp("picked_up_at")?.toInstant(),
        cancelledAt = getTimestamp("cancelled_at")?.toInstant(),
        cancelReason = getString("cancel_reason")?.let(CancelReason::valueOf),
        version = getLong("version"),
        couponId = getObject("coupon_id", UUID::class.java),
        couponAmount = getLong("coupon_amount"),
    )

    private data class Header(
        val id: UUID,
        val memberId: Long,
        val storeId: Long,
        val brandId: Long,
        val status: OrderStatus,
        val promisedPickupAt: Instant,
        val pointAmount: Long,
        val holdExpiresAt: Instant,
        val createdAt: Instant,
        val paidAt: Instant?,
        val preparingAt: Instant?,
        val readyAt: Instant?,
        val pickedUpAt: Instant?,
        val cancelledAt: Instant?,
        val cancelReason: CancelReason?,
        val version: Long,
        val couponId: UUID?,
        val couponAmount: Long,
    )

    private fun ts(i: Instant) = Timestamp.from(i)
}
