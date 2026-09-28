package com.brewslot.order.ordering.saga

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.messaging.contract.PaymentFailed
import com.brewslot.messaging.contract.PointsRedeemed
import com.brewslot.messaging.contract.PointsRedemptionFailed
import com.brewslot.messaging.inbox.Inbox
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

/** payment/loyalty 가 발행한 사실(event)을 Saga 응답으로 해석한다. 관심 없는 이벤트 타입은 무시한다. */
@Component
class SagaReplyListener(
    private val codec: EnvelopeCodec,
    private val inbox: Inbox,
    private val orchestrator: CheckoutSagaOrchestrator,
) {
    @KafkaListener(topics = [Topics.PAYMENT_EVENTS, Topics.LOYALTY_EVENTS], groupId = "order-saga")
    fun onReply(message: String) {
        val envelope = codec.decode(message)
        val handler: (() -> Unit)? = when (envelope.eventType) {
            PointsRedeemed::class.simpleName -> {
                { orchestrator.onPointsRedeemed(orderId(codec.payloadOf<PointsRedeemed>(envelope).orderId)) }
            }
            PointsRedemptionFailed::class.simpleName -> {
                codec.payloadOf<PointsRedemptionFailed>(envelope).let { e -> { orchestrator.onPointsRedemptionFailed(orderId(e.orderId), e.reason) } }
            }
            PaymentCaptured::class.simpleName -> {
                { orchestrator.onPaymentCaptured(orderId(codec.payloadOf<PaymentCaptured>(envelope).orderId)) }
            }
            PaymentFailed::class.simpleName -> {
                { orchestrator.onPaymentFailed(orderId(codec.payloadOf<PaymentFailed>(envelope).orderId)) }
            }
            else -> null
        }
        handler?.let { inbox.process(CONSUMER, envelope, it) }
    }

    private fun orderId(value: String) = UUID.fromString(value)

    companion object {
        const val CONSUMER = "order-saga"
    }
}
