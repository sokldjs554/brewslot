package com.brewslot.order.ordering.api

import com.brewslot.order.ordering.application.OrderLifecycleService
import com.brewslot.order.ordering.application.PlaceOrderCommand
import com.brewslot.order.ordering.application.PlaceOrderService
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.ordering.saga.CheckoutSagaOrchestrator
import com.brewslot.order.query.ReorderService
import com.brewslot.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * 고객용 주문 API. 인증은 API Gateway 에서 끝났다고 가정하고, 게이트웨이가 넣어 주는 X-Member-Id 를 신뢰한다.
 */
@Tag(name = "Orders")
@Validated
@RestController
@RequestMapping("/orders")
class OrderController(
    private val placeOrder: PlaceOrderService,
    private val saga: CheckoutSagaOrchestrator,
    private val lifecycle: OrderLifecycleService,
    private val reorders: ReorderService,
    private val orders: JdbcOrderRepository,
) {
    @Operation(summary = "주문 생성 (픽업 슬롯 임시 점유)")
    @PostMapping
    fun place(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @RequestHeader(IDEMPOTENCY_HEADER) @Pattern(regexp = KEY_PATTERN) idempotencyKey: String,
        @Valid @RequestBody request: PlaceOrderRequest,
    ): ResponseEntity<OrderResponse> {
        val result = placeOrder.place(
            PlaceOrderCommand(
                memberId = memberId,
                storeId = request.storeId,
                pickupAt = request.pickupAt,
                items = request.items.map { it.toCartItem() },
                pointsToUse = request.pointsToUse,
                idempotencyKey = idempotencyKey,
            ),
        )
        return created(result.order, result.replayed)
    }

    @Operation(summary = "주문 조회")
    @GetMapping("/{orderId}")
    fun get(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @PathVariable orderId: UUID,
    ): OrderResponse {
        val order = orders.findById(orderId)?.takeIf { it.memberId == memberId } ?: throw NotFoundException("order", orderId)
        return OrderResponse.of(order)
    }

    @Operation(summary = "결제 시작 (비동기 Saga). 결과는 GET /orders/{orderId} 로 확인")
    @PostMapping("/{orderId}/payment")
    fun pay(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @PathVariable orderId: UUID,
        @RequestBody request: PaymentRequest,
    ): ResponseEntity<OrderResponse> {
        val order = saga.start(memberId, orderId, request.cardToken)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .location(URI.create("/orders/${order.id}"))
            .body(OrderResponse.of(order))
    }

    @Operation(summary = "주문 취소 (제조 시작 전까지). 환불은 비동기로 진행")
    @PostMapping("/{orderId}/cancellation")
    fun cancel(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @PathVariable orderId: UUID,
    ): OrderResponse =
        OrderResponse.of(lifecycle.cancelByCustomer(memberId, orderId))

    @Operation(summary = "지난 주문과 같은 구성으로 재주문 (가격·품절은 현재 기준으로 재검증)")
    @PostMapping("/{orderId}/reorders")
    fun reorder(
        @RequestHeader(MEMBER_HEADER) memberId: Long,
        @RequestHeader(IDEMPOTENCY_HEADER) @Pattern(regexp = KEY_PATTERN) idempotencyKey: String,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: ReorderRequest,
    ): ResponseEntity<OrderResponse> {
        val result = reorders.reorder(memberId, orderId, request.pickupAt, request.pointsToUse, idempotencyKey)
        return created(result.order, result.replayed)
    }

    private fun created(order: com.brewslot.order.ordering.domain.Order, replayed: Boolean): ResponseEntity<OrderResponse> {
        val builder = if (replayed) ResponseEntity.ok() else ResponseEntity.created(URI.create("/orders/${order.id}"))
        return builder.header("Idempotent-Replayed", replayed.toString()).body(OrderResponse.of(order))
    }

    companion object {
        const val MEMBER_HEADER = "X-Member-Id"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val KEY_PATTERN = "^[A-Za-z0-9_-]{8,100}$"
    }
}
