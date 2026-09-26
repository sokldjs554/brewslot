package com.brewslot.messaging.contract

import java.time.Instant

// payment.commands (발행: order-service Saga, 수신: payment-service)

data class ChargePayment(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val amount: Long,
    val cardToken: String,
)

/** 결제가 없었거나 결과를 알 수 없는 경우에도 안전하게 보낼 수 있다(멱등, "있으면 취소"). */
data class RefundPayment(
    val orderId: String,
    val reason: String,
)

// payment.events (발행: payment-service)

data class PaymentCaptured(
    val paymentId: String,
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val amount: Long,
    val pgTransactionId: String,
    val capturedAt: Instant,
)

data class PaymentFailed(
    val orderId: String,
    val reason: String,
    val failedAt: Instant,
)

data class PaymentRefunded(
    val paymentId: String,
    val orderId: String,
    val storeId: Long,
    val brandId: Long,
    val amount: Long,
    val pgTransactionId: String,
    val refundedAt: Instant,
)
