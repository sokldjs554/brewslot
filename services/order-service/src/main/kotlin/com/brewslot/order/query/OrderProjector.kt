package com.brewslot.order.query

import com.brewslot.common.BusinessTime
import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.OrderCancelled
import com.brewslot.messaging.contract.OrderPaid
import com.brewslot.messaging.contract.OrderPickedUp
import com.brewslot.messaging.contract.OrderPlaced
import com.brewslot.messaging.contract.OrderPreparing
import com.brewslot.messaging.contract.OrderReady
import com.brewslot.messaging.inbox.Inbox
import com.brewslot.order.catalog.application.CatalogService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant

/**
 * CQRS 조회 모델 투영기. order.events 를 구독해 화면/통계에 맞춘 비정규화 테이블을 갱신한다.
 *
 * 쓰기 모델(orders)과 같은 DB 에 있지만 일부러 Kafka 를 거친다: 조회 모델을 다른 저장소(Elasticsearch 등)나
 * 다른 서비스로 옮기더라도 투영 코드가 그대로 동작하고, 쓰기 트랜잭션이 통계 갱신 때문에 길어지지 않는다.
 * 대가는 최종적 일관성 — 주문 직후 목록에는 잠깐 보이지 않을 수 있다(단건 조회는 쓰기 모델을 본다).
 */
@Component
class OrderProjector(
    private val codec: EnvelopeCodec,
    private val inbox: Inbox,
    private val jdbc: JdbcClient,
    private val catalog: CatalogService,
) {
    @KafkaListener(topics = [Topics.ORDER_EVENTS], groupId = "order-query-projector")
    fun on(message: String) {
        val envelope = codec.decode(message)
        inbox.process(CONSUMER, envelope) { project(envelope) }
    }

    fun project(envelope: EventEnvelope) {
        when (envelope.eventType) {
            OrderPlaced::class.simpleName -> placed(codec.payloadOf(envelope))
            OrderPaid::class.simpleName -> codec.payloadOf<OrderPaid>(envelope).let { e ->
                status(e.orderId, "PAID", e.paidAt)
                stats(e.storeId, e.paidAt, "paid_orders = paid_orders + 1, gross_amount = gross_amount + ${e.totalAmount}")
            }
            OrderPreparing::class.simpleName -> codec.payloadOf<OrderPreparing>(envelope).let {
                status(it.orderId, "PREPARING", it.acceptedAt)
            }
            OrderReady::class.simpleName -> codec.payloadOf<OrderReady>(envelope).let { e ->
                status(e.orderId, "READY", e.readyAt)
                val lateness = e.readyAt.epochSecond - e.promisedPickupAt.epochSecond
                val onTime = if (lateness <= 0) 1 else 0
                stats(
                    e.storeId,
                    e.promisedPickupAt,
                    "ready_orders = ready_orders + 1, on_time_orders = on_time_orders + $onTime, " +
                        "total_lateness_seconds = total_lateness_seconds + ${maxOf(0, lateness)}",
                )
            }
            OrderPickedUp::class.simpleName -> codec.payloadOf<OrderPickedUp>(envelope).let { e ->
                status(e.orderId, "PICKED_UP", e.pickedUpAt)
                stats(e.storeId, e.promisedPickupAt, "picked_up_orders = picked_up_orders + 1")
            }
            OrderCancelled::class.simpleName -> codec.payloadOf<OrderCancelled>(envelope).let { e ->
                status(e.orderId, "CANCELLED", e.cancelledAt)
                if (e.wasPaid) {
                    stats(e.storeId, e.cancelledAt, "cancelled_orders = cancelled_orders + 1")
                }
            }
        }
    }

    private fun placed(e: OrderPlaced) {
        val storeName = runCatching { catalog.store(e.storeId).name }.getOrDefault("store-${e.storeId}")
        jdbc.sql(
            """
            INSERT INTO member_order_view (order_id, member_id, store_id, store_name, status, total_amount, promised_pickup_at,
                                           items, cart_signature, placed_at, updated_at)
            VALUES (CAST(:orderId AS uuid), :memberId, :storeId, :storeName, 'PENDING_PAYMENT', :total, :pickupAt,
                    CAST(:items AS jsonb), :signature, :placedAt, :placedAt)
            ON CONFLICT (order_id) DO NOTHING
            """.trimIndent(),
        )
            .param("orderId", e.orderId)
            .param("memberId", e.memberId)
            .param("storeId", e.storeId)
            .param("storeName", storeName)
            .param("total", e.totalAmount)
            .param("pickupAt", Timestamp.from(e.promisedPickupAt))
            .param("items", codec.mapper.writeValueAsString(e.items))
            .param("signature", cartSignature(e.storeId, e.items.map { it.menuItemId to it.quantity }))
            .param("placedAt", Timestamp.from(e.placedAt))
            .update()
    }

    /** 이벤트가 역순으로 도착해도 더 오래된 사실이 최신 상태를 덮어쓰지 않도록 updated_at 을 비교한다. */
    private fun status(orderId: String, status: String, at: Instant) {
        jdbc.sql(
            """
            UPDATE member_order_view SET status = :status, updated_at = :at
            WHERE order_id = CAST(:orderId AS uuid) AND updated_at <= :at
            """.trimIndent(),
        ).param("status", status).param("at", Timestamp.from(at)).param("orderId", orderId).update()
    }

    private fun stats(storeId: Long, at: Instant, setClause: String) {
        jdbc.sql(
            """
            INSERT INTO store_daily_stats (store_id, business_date) VALUES (:storeId, :date)
            ON CONFLICT (store_id, business_date) DO NOTHING
            """.trimIndent(),
        ).param("storeId", storeId).param("date", BusinessTime.businessDateOf(at)).update()
        jdbc.sql("UPDATE store_daily_stats SET $setClause WHERE store_id = :storeId AND business_date = :date")
            .param("storeId", storeId)
            .param("date", BusinessTime.businessDateOf(at))
            .update()
    }

    companion object {
        const val CONSUMER = "order-query-projector"

        /** 매장 + (메뉴, 수량) 조합이 같으면 같은 "장바구니"로 본다. 재주문 추천의 그룹 키. */
        fun cartSignature(storeId: Long, items: List<Pair<Long, Int>>): String {
            val canonical = "$storeId|" + items.sortedBy { it.first }.joinToString(",") { "${it.first}x${it.second}" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
