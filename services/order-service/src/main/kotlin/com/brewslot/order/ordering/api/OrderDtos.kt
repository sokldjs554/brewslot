package com.brewslot.order.ordering.api

import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.scheduling.application.CartItem
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class OrderItemRequest(
    val menuItemId: Long,
    @field:Min(1) @field:Max(20) val quantity: Int,
) {
    fun toCartItem() = CartItem(menuItemId, quantity)
}

data class PlaceOrderRequest(
    val storeId: Long,
    val pickupAt: Instant,
    @field:NotEmpty @field:Size(max = 30) @field:Valid val items: List<OrderItemRequest>,
    @field:PositiveOrZero val pointsToUse: Long = 0,
    /** 적용할 쿠폰 (GET /members/{id}/coupons 에서 받은 것). couponAmount 는 그때 본 할인액이며 결제 단계에서 검증된다. */
    val couponId: UUID? = null,
    @field:PositiveOrZero val couponAmount: Long = 0,
)

data class PaymentRequest(
    /** PG 가 발급한 결제수단 토큰 (카드번호가 아님). 포인트로 전액 결제하면 비워도 된다. */
    val cardToken: String = "",
)

data class ReorderRequest(
    val pickupAt: Instant,
    @field:PositiveOrZero val pointsToUse: Long = 0,
)

data class OrderLineResponse(
    val menuItemId: Long,
    val name: String,
    val unitPrice: Long,
    val quantity: Int,
    val amount: Long,
)

data class OrderResponse(
    val orderId: String,
    val status: String,
    val storeId: Long,
    val promisedPickupAt: Instant,
    val totalAmount: Long,
    val couponId: String?,
    val couponAmount: Long,
    val pointAmount: Long,
    val cardAmount: Long,
    val holdExpiresAt: Instant?,
    val lines: List<OrderLineResponse>,
    val createdAt: Instant,
    val paidAt: Instant?,
    val readyAt: Instant?,
    val pickedUpAt: Instant?,
    val cancelledAt: Instant?,
    val cancelReason: String?,
) {
    companion object {
        fun of(o: Order) = OrderResponse(
            orderId = o.id.toString(),
            status = o.status.name,
            storeId = o.storeId,
            promisedPickupAt = o.promisedPickupAt,
            totalAmount = o.totalAmount.won,
            couponId = o.couponId?.toString(),
            couponAmount = o.couponAmount.won,
            pointAmount = o.pointAmount.won,
            cardAmount = o.cardAmount.won,
            holdExpiresAt = o.holdExpiresAt.takeIf { o.status.name == "PENDING_PAYMENT" },
            lines = o.lines.map { OrderLineResponse(it.menuItemId, it.name, it.unitPrice.won, it.quantity, it.amount.won) },
            createdAt = o.createdAt,
            paidAt = o.paidAt,
            readyAt = o.readyAt,
            pickedUpAt = o.pickedUpAt,
            cancelledAt = o.cancelledAt,
            cancelReason = o.cancelReason?.name,
        )
    }
}

data class StoreStatusRequest(val status: com.brewslot.order.ordering.application.StoreAction)
