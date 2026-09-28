package com.brewslot.messaging.contract

import java.time.Instant

// loyalty.commands (발행: order-service Saga, 수신: loyalty-service)

/**
 * 주문의 혜택(쿠폰 + 포인트)을 한 번에 사용한다. 둘 다 loyalty DB 에 있으므로 loyalty-service 의 로컬 트랜잭션 하나로
 * "둘 다 쓰거나 둘 다 안 쓰거나" 가 보장된다. 쿠폰 할인액은 주문 시 고객이 본 금액이며, loyalty-service 가 쿠폰의 실제
 * 할인액과 일치하는지 검증한다(불일치 → 실패 응답 → 주문 취소).
 */
data class RedeemPoints(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val storeId: Long,
    val amount: Long,
    val couponId: String? = null,
    val couponAmount: Long = 0,
    val orderTotal: Long = 0,
)

/** 보상 트랜잭션. 사용 내역이 없으면 아무 일도 하지 않는다(멱등). */
data class ReverseRedemption(
    val orderId: String,
    val reason: String,
)

// loyalty.events (발행: loyalty-service)

data class PointsRedeemed(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val storeId: Long,
    val amount: Long,
    val redeemedAt: Instant,
    val couponId: String? = null,
    val couponAmount: Long = 0,
)

data class PointsRedemptionFailed(
    val orderId: String,
    val reason: String,
    val availableBalance: Long,
    val failedAt: Instant,
)

data class PointsRedemptionReversed(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val storeId: Long,
    val amount: Long,
    val reversedAt: Instant,
    val couponId: String? = null,
    val couponAmount: Long = 0,
    /** 쿠폰을 되돌렸을 때 결과: ISSUED(다시 사용 가능) 또는 EXPIRED(그 사이 유효기간이 지남) */
    val couponRestoredAs: String? = null,
)

data class PointsEarned(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val amount: Long,
    val expiresAt: Instant,
    val earnedAt: Instant,
)
