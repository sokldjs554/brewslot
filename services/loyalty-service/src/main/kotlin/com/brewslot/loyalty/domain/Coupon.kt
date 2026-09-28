package com.brewslot.loyalty.domain

import java.time.Instant
import java.util.UUID

enum class CouponStatus { ISSUED, USED, EXPIRED }

/** 쿠폰 사용 거절 사유. Saga 실패 응답(PointsRedemptionFailed.reason)으로 그대로 나간다. */
enum class CouponRejection {
    COUPON_NOT_FOUND,
    COUPON_NOT_OWNED,
    COUPON_ALREADY_USED,
    COUPON_EXPIRED,
    COUPON_AMOUNT_MISMATCH,
    COUPON_MIN_ORDER_NOT_MET,
}

/**
 * 회원이 가진 쿠폰 한 장. 규칙은 전부 순수 함수로 두고, 잠금·저장은 애플리케이션 계층이 한다.
 */
data class Coupon(
    val id: UUID,
    val campaignId: Long,
    val memberId: Long,
    val brandId: Long,
    val discountAmount: Long,
    val minOrderAmount: Long,
    val status: CouponStatus,
    val expiresAt: Instant,
    val usedOrderId: UUID?,
) {
    /** 화면에 보여 줄 상태: 저장된 상태가 ISSUED 여도 유효기간이 지났으면 만료로 본다. */
    fun effectiveStatus(now: Instant): CouponStatus =
        if (status == CouponStatus.ISSUED && !now.isBefore(expiresAt)) CouponStatus.EXPIRED else status

    /**
     * 주문에 쓸 수 있는지 검사한다. [claimedAmount] 는 주문 시 고객이 본 할인액으로,
     * 그 사이 쿠폰이 바뀌었거나 조작된 요청이면 금액이 달라 거절된다.
     */
    fun rejectionFor(memberId: Long, brandId: Long, claimedAmount: Long, orderTotal: Long, now: Instant): CouponRejection? = when {
        this.memberId != memberId || this.brandId != brandId -> CouponRejection.COUPON_NOT_OWNED
        status == CouponStatus.USED -> CouponRejection.COUPON_ALREADY_USED
        effectiveStatus(now) == CouponStatus.EXPIRED -> CouponRejection.COUPON_EXPIRED
        claimedAmount != discountAmount -> CouponRejection.COUPON_AMOUNT_MISMATCH
        orderTotal < minOrderAmount -> CouponRejection.COUPON_MIN_ORDER_NOT_MET
        else -> null
    }

    /**
     * 결제가 취소돼 쿠폰을 돌려줄 때의 상태. 유효기간이 남았으면 다시 쓸 수 있고,
     * 결제 중에 유효기간이 지났다면 되살리지 않는다(기간 연장은 별도 운영 정책).
     */
    fun restoredStatus(now: Instant): CouponStatus = if (now.isBefore(expiresAt)) CouponStatus.ISSUED else CouponStatus.EXPIRED
}
