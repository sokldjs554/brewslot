package com.brewslot.messaging.contract

import java.time.Instant

// loyalty.commands (발행: order-service Saga, 수신: loyalty-service)

data class RedeemPoints(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val storeId: Long,
    val amount: Long,
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
)

data class PointsEarned(
    val orderId: String,
    val memberId: Long,
    val brandId: Long,
    val amount: Long,
    val expiresAt: Instant,
    val earnedAt: Instant,
)
