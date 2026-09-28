package com.brewslot.settlement.application

import com.brewslot.common.BusinessTime
import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.OrderTransferred
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.messaging.contract.PaymentRefunded
import com.brewslot.messaging.contract.PointsRedeemed
import com.brewslot.messaging.contract.PointsRedemptionReversed
import com.brewslot.settlement.domain.EntryKind
import com.brewslot.settlement.domain.SettlementCalculator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 돈이 움직였다는 "사실" 이벤트만 정산 원천으로 적재한다(주문 상태 이벤트가 아니라).
 * 이벤트 ID 유니크 제약이 자연스러운 멱등 키가 되므로 별도 Inbox 가 필요 없다.
 */
@Component
class SettlementIngestor(
    private val codec: EnvelopeCodec,
    private val jdbc: JdbcClient,
    private val props: SettlementProperties,
) {
    @KafkaListener(topics = [Topics.PAYMENT_EVENTS, Topics.LOYALTY_EVENTS], groupId = "settlement-service")
    fun on(message: String) = ingest(codec.decode(message))

    /** 주문 이벤트 중 돈의 귀속이 바뀌는 사실(매장 변경)만 받는다. */
    @KafkaListener(topics = [Topics.ORDER_EVENTS], groupId = "settlement-service-orders")
    fun onOrderEvent(message: String) = ingest(codec.decode(message))

    fun ingest(envelope: EventEnvelope) {
        when (envelope.eventType) {
            PaymentCaptured::class.simpleName -> codec.payloadOf<PaymentCaptured>(envelope).let {
                insert(envelope.eventId, it.storeId, it.brandId, it.orderId, EntryKind.CARD_SALE, it.amount, it.pgTransactionId, it.capturedAt)
            }
            PaymentRefunded::class.simpleName -> codec.payloadOf<PaymentRefunded>(envelope).let {
                insert(envelope.eventId, it.storeId, it.brandId, it.orderId, EntryKind.CARD_REFUND, -it.amount, it.pgTransactionId, it.refundedAt)
            }
            // 한 이벤트에 포인트와 쿠폰이 함께 담겨 온다. 원천 행은 수단별로 나누고, 쿠폰 행의 멱등 키는 이벤트 ID 에서 결정적으로 유도한다.
            PointsRedeemed::class.simpleName -> codec.payloadOf<PointsRedeemed>(envelope).let {
                if (it.amount > 0) insert(envelope.eventId, it.storeId, it.brandId, it.orderId, EntryKind.POINT_SALE, it.amount, null, it.redeemedAt)
                if (it.couponAmount > 0) {
                    insert(couponKey(envelope.eventId), it.storeId, it.brandId, it.orderId, EntryKind.COUPON_SALE, it.couponAmount, null, it.redeemedAt)
                }
            }
            // 매출과 그 카드 수수료를 원래 매장에서 새 매장으로 옮긴다. 이후 환불 · 사용 취소는 새 매장 기준으로 들어온다.
            OrderTransferred::class.simpleName -> codec.payloadOf<OrderTransferred>(envelope).let {
                val fee = SettlementCalculator.cardFee(it.cardAmount, props.pgFeeBps)
                val out = derivedKey(envelope.eventId, "out")
                val inKey = derivedKey(envelope.eventId, "in")
                insert(out, it.fromStoreId, it.brandId, it.orderId, EntryKind.TRANSFER_OUT, -it.totalAmount, null, it.transferredAt, -fee)
                insert(inKey, it.toStoreId, it.brandId, it.orderId, EntryKind.TRANSFER_IN, it.totalAmount, null, it.transferredAt, fee)
            }
            PointsRedemptionReversed::class.simpleName -> codec.payloadOf<PointsRedemptionReversed>(envelope).let {
                if (it.amount > 0) insert(envelope.eventId, it.storeId, it.brandId, it.orderId, EntryKind.POINT_REVERSAL, -it.amount, null, it.reversedAt)
                if (it.couponAmount > 0) {
                    insert(
                        couponKey(envelope.eventId),
                        it.storeId,
                        it.brandId,
                        it.orderId,
                        EntryKind.COUPON_REVERSAL,
                        -it.couponAmount,
                        null,
                        it.reversedAt,
                    )
                }
            }
        }
    }

    private fun couponKey(eventId: UUID): UUID = derivedKey(eventId, "coupon")

    private fun derivedKey(eventId: UUID, part: String): UUID = UUID.nameUUIDFromBytes("$eventId:$part".toByteArray())

    private fun insert(
        eventId: UUID,
        storeId: Long,
        brandId: Long,
        orderId: String,
        kind: EntryKind,
        amount: Long,
        pgTxId: String?,
        at: Instant,
        pgFee: Long? = null,
    ) {
        val fee = pgFee ?: if (kind.isCard) SettlementCalculator.cardFee(amount, props.pgFeeBps) else 0
        jdbc.sql(
            """
            INSERT INTO settlement_entry (source_event_id, store_id, brand_id, order_id, kind, amount, pg_fee, pg_transaction_id, occurred_at, business_date)
            VALUES (:eventId, :storeId, :brandId, CAST(:orderId AS uuid), :kind, :amount, :fee, :pgTx, :at, :date)
            ON CONFLICT (source_event_id) DO NOTHING
            """.trimIndent(),
        )
            .param("eventId", eventId)
            .param("storeId", storeId)
            .param("brandId", brandId)
            .param("orderId", orderId)
            .param("kind", kind.name)
            .param("amount", amount)
            .param("fee", fee)
            .param("pgTx", pgTxId)
            .param("at", Timestamp.from(at))
            .param("date", BusinessTime.businessDateOf(at))
            .update()
    }
}
