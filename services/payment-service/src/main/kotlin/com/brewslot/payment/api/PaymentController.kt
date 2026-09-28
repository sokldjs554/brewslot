package com.brewslot.payment.api

import com.brewslot.messaging.Topics
import com.brewslot.payment.application.JdbcPaymentRepository
import com.brewslot.payment.domain.Payment
import com.brewslot.payment.fakepg.FakePgClient
import com.brewslot.payment.fakepg.PgSettlementLine
import com.brewslot.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.kafka.config.TopicBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PaymentResponse(
    val paymentId: String,
    val orderId: String,
    /** 매출이 귀속되는 매장 (매장 변경된 주문은 환불 시점에 새 매장으로 바뀐다) */
    val storeId: Long,
    val amount: Long,
    val status: String,
    val pgTransactionId: String?,
    val failureReason: String?,
    val capturedAt: Instant?,
    val refundedAt: Instant?,
) {
    companion object {
        fun of(p: Payment) = PaymentResponse(
            p.id.toString(),
            p.orderId.toString(),
            p.storeId,
            p.amount,
            p.status.name,
            p.pgTransactionId,
            p.failureReason,
            p.capturedAt,
            p.refundedAt,
        )
    }
}

data class GhostTransactionRequest(val amount: Long)

@Tag(name = "Payments")
@RestController
class PaymentController(private val payments: JdbcPaymentRepository, private val fakePg: FakePgClient) {
    @Operation(summary = "주문의 결제 상태")
    @GetMapping("/payments/{orderId}")
    fun get(
        @PathVariable orderId: UUID,
    ): PaymentResponse =
        payments.findByOrderId(orderId)?.let(PaymentResponse::of) ?: throw NotFoundException("payment", orderId)

    @Operation(summary = "[Fake PG] 거래일 기준 PG 정산(대사) 파일")
    @GetMapping("/pg/settlement-files/{date}")
    fun settlementFile(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): List<PgSettlementLine> =
        fakePg.settlementFile(date)

    @Operation(summary = "[Fake PG] 대사 데모용 - 우리 원장에 없는 PG 승인 건 주입")
    @PostMapping("/pg/admin/ghost-transactions")
    fun ghost(
        @RequestBody request: GhostTransactionRequest,
    ): Map<String, String> =
        mapOf("transactionId" to fakePg.injectGhostApproval(request.amount))
}

@Configuration
class PaymentTopics {
    @Bean
    fun paymentEventsTopic(): NewTopic = TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(3).build()
}
