package com.brewslot.payment.application

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.ChargePayment
import com.brewslot.messaging.contract.RefundPayment
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentCommandListener(private val codec: EnvelopeCodec, private val service: PaymentService) {
    @KafkaListener(topics = [Topics.PAYMENT_COMMANDS], groupId = "payment-service")
    fun on(message: String) {
        val envelope = codec.decode(message)
        when (envelope.eventType) {
            ChargePayment::class.simpleName -> service.charge(envelope, codec.payloadOf(envelope))
            RefundPayment::class.simpleName -> service.refund(envelope, codec.payloadOf(envelope))
        }
    }
}
