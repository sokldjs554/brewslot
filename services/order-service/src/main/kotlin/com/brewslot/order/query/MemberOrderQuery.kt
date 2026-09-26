package com.brewslot.order.query

import com.brewslot.messaging.contract.OrderedItem
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class OrderSummary(
    val orderId: String,
    val storeId: Long,
    val storeName: String,
    val status: String,
    val totalAmount: Long,
    val promisedPickupAt: Instant,
    val placedAt: Instant,
    val items: List<OrderedItem>,
)

data class OrderPage(val items: List<OrderSummary>, val nextCursor: String?)

/**
 * 커서(keyset) 페이지네이션.
 * OFFSET 은 뒤 페이지로 갈수록 버리는 행이 늘어 O(offset) 이 되고, 중간에 새 주문이 끼면 중복/누락이 생긴다.
 * (placed_at, order_id) 복합 키로 "마지막으로 본 행 다음" 을 찾으면 페이지 깊이와 무관하게 일정한 비용이다.
 */
@Component
class MemberOrderQuery(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun page(memberId: Long, cursor: String?, size: Int): OrderPage {
        val limit = size.coerceIn(1, 50)
        val after = cursor?.let(::decode)
        val rows = jdbc.sql(
            """
            SELECT order_id, store_id, store_name, status, total_amount, promised_pickup_at, placed_at, items::text AS items
            FROM member_order_view
            WHERE member_id = :memberId
              ${if (after != null) "AND (placed_at, order_id) < (:afterPlacedAt, :afterId)" else ""}
            ORDER BY placed_at DESC, order_id DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("memberId", memberId)
            .param("limit", limit + 1)
            .apply {
                if (after != null) {
                    param("afterPlacedAt", Timestamp.from(after.first))
                    param("afterId", after.second)
                }
            }
            .query { rs, _ ->
                OrderSummary(
                    orderId = rs.getString("order_id"),
                    storeId = rs.getLong("store_id"),
                    storeName = rs.getString("store_name"),
                    status = rs.getString("status"),
                    totalAmount = rs.getLong("total_amount"),
                    promisedPickupAt = rs.getTimestamp("promised_pickup_at").toInstant(),
                    placedAt = rs.getTimestamp("placed_at").toInstant(),
                    items = mapper.readValue(rs.getString("items")),
                )
            }
            .list()
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { encode(it.placedAt, UUID.fromString(it.orderId)) } else null
        return OrderPage(page, next)
    }

    /** 가장 최근에 픽업까지 완료한 "서로 다른" 장바구니들 */
    fun recentDistinctCarts(memberId: Long, limit: Int): List<OrderSummary> = jdbc.sql(
        """
        SELECT DISTINCT ON (cart_signature)
               order_id, store_id, store_name, status, total_amount, promised_pickup_at, placed_at, items::text AS items
        FROM (
            SELECT * FROM member_order_view
            WHERE member_id = :memberId AND status = 'PICKED_UP'
            ORDER BY placed_at DESC
            LIMIT 50
        ) recent
        ORDER BY cart_signature, placed_at DESC
        """.trimIndent(),
    ).param("memberId", memberId).query { rs, _ ->
        OrderSummary(
            orderId = rs.getString("order_id"),
            storeId = rs.getLong("store_id"),
            storeName = rs.getString("store_name"),
            status = rs.getString("status"),
            totalAmount = rs.getLong("total_amount"),
            promisedPickupAt = rs.getTimestamp("promised_pickup_at").toInstant(),
            placedAt = rs.getTimestamp("placed_at").toInstant(),
            items = mapper.readValue(rs.getString("items")),
        )
    }.list().sortedByDescending { it.placedAt }.take(limit)

    private fun encode(placedAt: Instant, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${placedAt}_$id".toByteArray())

    private fun decode(cursor: String): Pair<Instant, UUID> = try {
        val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split('_')
        Instant.parse(at) to UUID.fromString(id)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid cursor")
    }
}
