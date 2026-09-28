package com.brewslot.order.ordering.api

import com.brewslot.order.ordering.api.OrderController.Companion.IDEMPOTENCY_HEADER
import com.brewslot.order.ordering.api.OrderController.Companion.KEY_PATTERN
import com.brewslot.order.ordering.api.OrderController.Companion.MEMBER_HEADER
import com.brewslot.order.ordering.application.RequestTransferCommand
import com.brewslot.order.ordering.application.StoreTransferService
import com.brewslot.order.ordering.application.TransferOptions
import com.brewslot.order.ordering.domain.StoreTransfer
import com.brewslot.order.ordering.domain.TransferStatus
import com.brewslot.web.ConflictException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

data class TransferRequest(val toStoreId: Long, val pickupAt: Instant)

data class TransferItem(val menuItemId: Long, val name: String, val quantity: Int, val unitPrice: Long)

data class TransferResponse(
    val transferId: UUID,
    val orderId: UUID,
    val fromStoreId: Long,
    val toStoreId: Long,
    val previousPickupAt: Instant,
    val pickupAt: Instant,
    val status: TransferStatus,
    val failReason: String?,
    val requestedAt: Instant,
    val deadlineAt: Instant,
    val decidedAt: Instant?,
    val items: List<TransferItem>,
) {
    companion object {
        fun of(t: StoreTransfer) = TransferResponse(
            t.id, t.orderId, t.fromStoreId, t.toStoreId, t.previousPickupAt, t.pickupAt, t.status, t.failReason?.name,
            t.requestedAt, t.deadlineAt, t.decidedAt,
            t.newLines.map { TransferItem(it.menuItemId, it.name, it.quantity, it.unitPrice.won) },
        )
    }
}

/** 매장 변경: 고객(변경 후보 조회 · 요청 · 상태 확인)과 새 매장 태블릿(수락 · 거절) */
@Tag(name = "Store transfer")
@Validated
@RestController
class StoreTransferController(private val transfers: StoreTransferService) {
    @Operation(summary = "매장 변경 후보: 같은 브랜드 매장별로 같은 음료 · 같은 가격 여부, 지금 약속 시각 가능 여부, 가까운 가능 시각")
    @GetMapping("/orders/{orderId}/transfer-options")
    fun options(@RequestHeader(MEMBER_HEADER) memberId: Long, @PathVariable orderId: UUID): TransferOptions =
        transfers.options(memberId, orderId)

    @Operation(summary = "매장 변경 요청 (새 매장 자리를 먼저 잡고 새 매장 수락을 기다림). 원래 주문은 수락 전까지 그대로 유효")
    @PostMapping("/orders/{orderId}/transfers")
    fun request(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @RequestHeader(IDEMPOTENCY_HEADER) @Pattern(regexp = KEY_PATTERN) idempotencyKey: String,
        @PathVariable orderId: UUID,
        @RequestBody request: TransferRequest,
    ): ResponseEntity<TransferResponse> {
        val result = transfers.request(RequestTransferCommand(memberId, orderId, request.toStoreId, request.pickupAt, idempotencyKey))
        // 자동 수락 매장이면 이 응답에서 이미 COMPLETED(200), 새 매장 확인이 필요하면 AWAITING_STORE(202)
        val builder = if (result.replayed ||
            result.transfer.status == TransferStatus.COMPLETED
        ) {
            ResponseEntity.ok()
        } else {
            ResponseEntity.status(HttpStatus.ACCEPTED)
        }
        return builder
            .location(URI.create("/orders/$orderId/transfers/${result.transfer.id}"))
            .header("Idempotent-Replayed", result.replayed.toString())
            .body(TransferResponse.of(result.transfer))
    }

    @Operation(summary = "매장 변경 요청 상태")
    @GetMapping("/orders/{orderId}/transfers/{transferId}")
    fun get(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @PathVariable orderId: UUID,
        @PathVariable transferId: UUID,
    ): TransferResponse = TransferResponse.of(transfers.find(memberId, orderId, transferId))

    @Operation(summary = "새 매장 태블릿: 수락 대기 중인 매장 변경 요청")
    @GetMapping("/stores/{storeId}/transfer-requests")
    fun pending(@PathVariable storeId: Long): List<TransferResponse> = transfers.pendingForStore(storeId).map(TransferResponse::of)

    @Operation(summary = "새 매장 수락 → 주문이 이 매장으로 옮겨짐 (재시도해도 같은 결과)")
    @PostMapping("/stores/{storeId}/transfer-requests/{transferId}/acceptance")
    fun accept(@PathVariable storeId: Long, @PathVariable transferId: UUID): TransferResponse =
        expect(transfers.accept(storeId, transferId), TransferStatus.COMPLETED)

    @Operation(summary = "새 매장 거절 → 잡아 둔 자리를 풀고 원래 주문 유지")
    @PostMapping("/stores/{storeId}/transfer-requests/{transferId}/refusal")
    fun refuse(@PathVariable storeId: Long, @PathVariable transferId: UUID): TransferResponse =
        expect(transfers.refuse(storeId, transferId), TransferStatus.REFUSED)

    private fun expect(t: StoreTransfer, wanted: TransferStatus): TransferResponse {
        if (t.status != wanted) {
            throw ConflictException(
                "transfer-closed",
                "이미 처리된 매장 변경 요청입니다 (${t.status}).",
                mapOf("status" to t.status.name, "failReason" to t.failReason?.name),
            )
        }
        return TransferResponse.of(t)
    }
}
