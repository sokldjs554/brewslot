package com.brewslot.payment.domain

import java.time.Instant
import java.util.UUID

/**
 * REQUESTED ─approve─▶ CAPTURED ─cancel─▶ REFUNDED
 *     │  └──decline──▶ FAILED
 *     └──timeout──▶ UNKNOWN ─(PG 조회로 복구)─▶ CAPTURED | FAILED
 */
enum class PaymentStatus { REQUESTED, CAPTURED, FAILED, UNKNOWN, REFUNDED }

data class Payment(
    val id: UUID,
    val orderId: UUID,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val amount: Long,
    val cardToken: String,
    val status: PaymentStatus,
    val pgTransactionId: String?,
    val pgCancelId: String?,
    val failureReason: String?,
    val refundRequested: Boolean,
    val createdAt: Instant,
    val capturedAt: Instant?,
    val refundedAt: Instant?,
)
