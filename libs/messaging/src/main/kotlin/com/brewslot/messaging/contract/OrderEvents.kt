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

/**
 * 결제된 주문이 같은 브랜드의 다른 매장으로 옮겨졌다(새 매장 수락 시점).
 * 결제 금액은 바뀌지 않고 "어느 매장의 매출인가" 만 바뀐다 → 정산은 매출을 원래 매장에서 새 매장으로 옮기고,
 * 포인트 원장은 매장 채권(clearing)을 옮긴다.
 */
data class OrderTransferred(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val fromStoreId: Long,
    val toStoreId: Long,
    val previousPickupAt: Instant,
    val promisedPickupAt: Instant,
    val totalAmount: Long,
    val cardAmount: Long,
    val pointAmount: Long,
    val couponAmount: Long,
    val items: List<OrderedItem>,
    val transferredAt: Instant,
)

/** 매장 변경이 이뤄지지 않았다. 원래 주문은 원래 매장에서 그대로 유효하다. (고객 알림용) */
data class OrderTransferFailed(
    val orderId: String,
    val memberId: Long,
    val fromStoreId: Long,
    val toStoreId: Long,
    /** REFUSED_BY_STORE | STORE_DID_NOT_RESPOND | ORIGINAL_STORE_STARTED | ORDER_CANCELLED */
    val reason: String,
    val failedAt: Instant,
)

/**
 * 매장이 밀려 약속한 픽업 시각을 못 지킬 것 같다 — 고객이 앱을 열기 전에 먼저 알린다(주문당 한 번).
 * 옮길 만한 근처 매장이 있으면 함께 제안하고, 고객은 한 번 눌러 옮긴다.
 */
data class PickupAtRisk(
    val orderId: String,
    val memberId: Long,
    val storeId: Long,
    val storeName: String,
    val promisedPickupAt: Instant,
    val expectedDelayMinutes: Int,
    val suggestedStoreId: Long?,
    val suggestedStoreName: String?,
    val suggestedPickupAt: Instant?,
    val walkMinutes: Int?,
    val detectedAt: Instant,
)
