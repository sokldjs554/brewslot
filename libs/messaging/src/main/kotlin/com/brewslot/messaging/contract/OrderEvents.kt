package com.brewslot.messaging.contract

import java.time.Instant

/*
 * order-service 가 발행하는 도메인 이벤트 (topic: order.events, key: orderId)
 *
 * 구독자(settlement, loyalty, order 자신의 CQRS projector)가 order-service 를 다시 조회하지 않도록
 * 필요한 사실을 이벤트에 담는다(Event-Carried State Transfer).
 */

data class OrderedItem(
    val menuItemId: Long,
    val name: String,
    val quantity: Int,
    val unitPrice: Long,
)

data class OrderPlaced(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val totalAmount: Long,
    val promisedPickupAt: Instant,
    val items: List<OrderedItem>,
    val placedAt: Instant,
)

data class OrderPaid(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val totalAmount: Long,
    val cardAmount: Long,
    val pointAmount: Long,
    val promisedPickupAt: Instant,
    val items: List<OrderedItem>,
    val paidAt: Instant,
    val couponAmount: Long = 0,
)

data class OrderPreparing(
    val orderId: String,
    val storeId: Long,
    val acceptedAt: Instant,
    /** 추가 필드(하위 호환): 알림 서비스가 주문→회원 매핑을 따로 들고 있지 않아도 되도록 */
    val memberId: Long = 0,
)

data class OrderReady(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val promisedPickupAt: Instant,
    val readyAt: Instant,
)

data class OrderPickedUp(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val totalAmount: Long,
    val cardAmount: Long,
    val pointAmount: Long,
    val promisedPickupAt: Instant,
    val readyAt: Instant,
    val pickedUpAt: Instant,
    val couponAmount: Long = 0,
)

data class OrderCancelled(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val brandId: Long,
    val reason: String,
    val wasPaid: Boolean,
    val cancelledAt: Instant,
)
