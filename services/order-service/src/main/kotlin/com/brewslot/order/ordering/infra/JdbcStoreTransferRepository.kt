package com.brewslot.order.ordering.infra

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.domain.StoreTransfer
import com.brewslot.order.ordering.domain.TransferFailReason
import com.brewslot.order.ordering.domain.TransferStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcStoreTransferRepository(private val jdbc: JdbcClient, private val json: ObjectMapper) {
    private data class LineJson(
        val lineNo: Int,
        val menuItemId: Long,
        val name: String,
        val unitPrice: Long,
        val quantity: Int,
        val station: String,
        val loadUnits: Int,
        val freshnessMinutes: Int,
    )

    fun insert(t: StoreTransfer, idempotencyKey: String, requestHash: String) {
        jdbc.sql(
            """
            INSERT INTO store_transfer (id, order_id, member_id, from_store_id, to_store_id, previous_pickup_at, pickup_at, status,
                                        new_lines, idempotency_key, request_hash, requested_at, deadline_at)
            VALUES (:id, :orderId, :memberId, :from, :to, :prev, :pickupAt, :status,
                    CAST(:lines AS jsonb), :key, :hash, :requestedAt, :deadlineAt)
            """.trimIndent(),
        )
            .param("id", t.id)
            .param("orderId", t.orderId)
            .param("memberId", t.memberId)
            .param("from", t.fromStoreId)
            .param("to", t.toStoreId)
            .param("prev", ts(t.previousPickupAt))
            .param("pickupAt", ts(t.pickupAt))
            .param("status", t.status.name)
            .param("lines", json.writeValueAsString(t.newLines.map { it.toJson() }))
            .param("key", idempotencyKey)
            .param("hash", requestHash)
            .param("requestedAt", ts(t.requestedAt))
            .param("deadlineAt", ts(t.deadlineAt))
            .update()
    }

    /** 상태 전이는 AWAITING_STORE 에서만 일어나므로, 조건부 UPDATE 로 "먼저 닫은 쪽만" 성공한다. */
    fun close(t: StoreTransfer): Boolean = jdbc.sql(
        """
        UPDATE store_transfer SET status = :status, fail_reason = :reason, decided_at = :decidedAt
        WHERE id = :id AND status = 'AWAITING_STORE'
        """.trimIndent(),
    )
        .param("status", t.status.name)
        .param("reason", t.failReason?.name)
        .param("decidedAt", t.decidedAt?.let(::ts))
        .param("id", t.id)
        .update() == 1

    fun findById(id: UUID, forUpdate: Boolean = false): StoreTransfer? =
        one("WHERE id = :id${if (forUpdate) " FOR UPDATE" else ""}", mapOf("id" to id))

    fun findOpenByOrderForUpdate(orderId: UUID): StoreTransfer? =
        one("WHERE order_id = :orderId AND status = 'AWAITING_STORE' FOR UPDATE", mapOf("orderId" to orderId))

    fun hasCompleted(orderId: UUID): Boolean = jdbc.sql(
        "SELECT EXISTS (SELECT 1 FROM store_transfer WHERE order_id = :orderId AND status = 'COMPLETED')",
    ).param("orderId", orderId).query(Boolean::class.java).single()

    fun findByIdempotencyKey(memberId: Long, key: String): Pair<StoreTransfer, String>? = jdbc.sql(
        "SELECT *, request_hash FROM store_transfer WHERE member_id = :memberId AND idempotency_key = :key",
    ).param("memberId", memberId).param("key", key).query { rs, _ -> rs.toTransfer() to rs.getString("request_hash") }
        .optional().orElse(null)

    fun byOrder(orderId: UUID): List<StoreTransfer> =
        many("WHERE order_id = :orderId ORDER BY requested_at DESC", mapOf("orderId" to orderId))

    /** 새 매장 태블릿의 수락 대기 목록 */
    fun pendingForStore(storeId: Long): List<StoreTransfer> =
        many("WHERE to_store_id = :storeId AND status = 'AWAITING_STORE' ORDER BY requested_at", mapOf("storeId" to storeId))

    /** 기한 초과 후보. 잠그지 않는다 — 건별 처리 트랜잭션이 주문 행부터 잠근다(잠금 순서 고정, ADR-0013). */
    fun overdueIds(now: Instant, limit: Int): List<UUID> = jdbc.sql(
        "SELECT id FROM store_transfer WHERE status = 'AWAITING_STORE' AND deadline_at <= :now ORDER BY deadline_at LIMIT :limit",
    ).param("now", ts(now)).param("limit", limit).query(UUID::class.java).list()

    private fun one(where: String, params: Map<String, Any>): StoreTransfer? =
        jdbc.sql("SELECT * FROM store_transfer $where").params(params).query { rs, _ -> rs.toTransfer() }.optional().orElse(null)

    private fun many(where: String, params: Map<String, Any>): List<StoreTransfer> =
        jdbc.sql("SELECT * FROM store_transfer $where").params(params).query { rs, _ -> rs.toTransfer() }.list()

    private fun ResultSet.toTransfer() = StoreTransfer.reconstitute(
        id = getObject("id", UUID::class.java),
        orderId = getObject("order_id", UUID::class.java),
        memberId = getLong("member_id"),
        fromStoreId = getLong("from_store_id"),
        toStoreId = getLong("to_store_id"),
        previousPickupAt = getTimestamp("previous_pickup_at").toInstant(),
        pickupAt = getTimestamp("pickup_at").toInstant(),
        newLines = json.readValue<List<LineJson>>(getString("new_lines")).map { it.toLine() },
        requestedAt = getTimestamp("requested_at").toInstant(),
        deadlineAt = getTimestamp("deadline_at").toInstant(),
        status = TransferStatus.valueOf(getString("status")),
        failReason = getString("fail_reason")?.let(TransferFailReason::valueOf),
        decidedAt = getTimestamp("decided_at")?.toInstant(),
    )

    private fun OrderLine.toJson() = LineJson(lineNo, menuItemId, name, unitPrice.won, quantity, station.name, loadUnits, freshnessMinutes)

    private fun LineJson.toLine() =
        OrderLine(lineNo, menuItemId, name, Money(unitPrice), quantity, Station.valueOf(station), loadUnits, freshnessMinutes)

    private fun ts(i: Instant) = Timestamp.from(i)
}
