package com.brewslot.loyalty

import com.brewslot.loyalty.domain.Coupon
import com.brewslot.loyalty.domain.CouponRejection
import com.brewslot.loyalty.domain.CouponStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

@DisplayName("쿠폰 사용 규칙")
class CouponTest {
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val coupon = Coupon(UUID.randomUUID(), 1, 7, 1, 1_000, 5_000, CouponStatus.ISSUED, now.plus(Duration.ofDays(3)), null)

    @Test
    fun `조건을 모두 만족하면 거절 사유가 없다`() {
        assertThat(coupon.rejectionFor(7, 1, 1_000, 8_500, now)).isNull()
    }

    @Test
    fun `다른 회원·다른 브랜드의 쿠폰은 쓸 수 없다`() {
        assertThat(coupon.rejectionFor(8, 1, 1_000, 8_500, now)).isEqualTo(CouponRejection.COUPON_NOT_OWNED)
        assertThat(coupon.rejectionFor(7, 2, 1_000, 8_500, now)).isEqualTo(CouponRejection.COUPON_NOT_OWNED)
    }

    @Test
    fun `주문 시 본 할인액과 실제 할인액이 다르면 거절한다 - 조작된 요청이나 그 사이 바뀐 쿠폰`() {
        assertThat(coupon.rejectionFor(7, 1, 3_000, 8_500, now)).isEqualTo(CouponRejection.COUPON_AMOUNT_MISMATCH)
    }

    @Test
    fun `최소 주문 금액 미만이면 거절한다`() {
        assertThat(coupon.rejectionFor(7, 1, 1_000, 4_999, now)).isEqualTo(CouponRejection.COUPON_MIN_ORDER_NOT_MET)
    }

    @Test
    fun `사용한 쿠폰과 만료된 쿠폰은 거절한다`() {
        assertThat(coupon.copy(status = CouponStatus.USED).rejectionFor(7, 1, 1_000, 8_500, now))
            .isEqualTo(CouponRejection.COUPON_ALREADY_USED)
        val expiry = coupon.expiresAt
        assertThat(coupon.rejectionFor(7, 1, 1_000, 8_500, expiry)).isEqualTo(CouponRejection.COUPON_EXPIRED)
        assertThat(coupon.effectiveStatus(expiry)).isEqualTo(CouponStatus.EXPIRED)
    }

    @Test
    fun `결제가 취소되면 유효기간이 남은 쿠폰만 다시 쓸 수 있게 되돌린다`() {
        assertThat(coupon.restoredStatus(now)).isEqualTo(CouponStatus.ISSUED)
        assertThat(coupon.restoredStatus(coupon.expiresAt.plusSeconds(1))).isEqualTo(CouponStatus.EXPIRED)
    }
}
