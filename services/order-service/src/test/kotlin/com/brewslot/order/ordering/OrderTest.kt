package com.brewslot.order.ordering

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.ordering.domain.CancelReason
import com.brewslot.order.ordering.domain.IllegalOrderTransitionException
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.saga.CheckoutSaga
import com.brewslot.order.ordering.saga.SagaOutOfOrderException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    private fun order(points: Long = 0, coupon: Long = 0) = Order.place(
        id = UUID.randomUUID(),
        memberId = 1,
        storeId = 101,
        brandId = 1,
        lines = listOf(
            OrderLine(1, 1003, "카페라떼", Money(4500), 2, Station.ESPRESSO, 2, 5),
            OrderLine(2, 1005, "딸기 스무디", Money(6000), 1, Station.BLENDER, 3, 10),
        ),
        promisedPickupAt = now.plusSeconds(1800),
        pointAmount = Money(points),
        holdExpiresAt = now.plusSeconds(300),
        now = now,
        couponId = if (coupon > 0) UUID.randomUUID() else null,
        couponAmount = Money(coupon),
    )

    @Test
    fun `금액 계산 - 카드 결제액은 총액에서 포인트를 뺀 값`() {
        val o = order(points = 3000)
        assertThat(o.totalAmount).isEqualTo(Money(15_000))
        assertThat(o.cardAmount).isEqualTo(Money(12_000))
    }

    @Test
    fun `포인트는 총액을 넘을 수 없다`() {
        assertThatThrownBy { order(points = 15_001) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `쿠폰 할인 후 포인트, 나머지를 카드로 - 포인트는 할인 후 금액을 넘을 수 없다`() {
        val o = order(points = 3000, coupon = 1000)
        assertThat(o.cardAmount).isEqualTo(Money(11_000))
        assertThat(o.hasBenefits).isTrue()
        assertThatThrownBy { order(points = 14_001, coupon = 1000) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Saga - 쿠폰만 있어도 혜택 사용 단계를 먼저 거친다`() {
        val saga = CheckoutSaga.start(UUID.randomUUID(), Money(0), Money(14_000), "tok", now, now.plusSeconds(120), Money(1000))
        assertThat(saga.firstStep()).isEqualTo(CheckoutSaga.Next.RedeemPoints)
        assertThat(saga.onPointsRedeemed()).isEqualTo(CheckoutSaga.Next.ChargeCard)
    }

    @Test
    fun `정상 흐름 - 결제 → 접수 → 완료 → 픽업`() {
        val o = order()
        o.startCheckout(now)
        o.markPaid(now.plusSeconds(3))
        o.accept(now.plusSeconds(600))
        o.markReady(now.plusSeconds(1700))
        o.handOff(now.plusSeconds(1800))
        assertThat(o.status).isEqualTo(OrderStatus.PICKED_UP)
        assertThat(o.latenessSeconds()).isEqualTo(-100)
    }

    @Test
    fun `점유 만료 이후에는 결제를 시작할 수 없다`() {
        assertThatThrownBy { order().startCheckout(now.plusSeconds(300)) }.isInstanceOf(IllegalOrderTransitionException::class.java)
    }

    @Test
    fun `제조가 시작되면 고객은 취소할 수 없다`() {
        val o = order().apply {
            startCheckout(now)
            markPaid(now)
            accept(now)
        }
        assertThatThrownBy { o.cancel(CancelReason.CUSTOMER_CANCELLED, now) }.isInstanceOf(IllegalOrderTransitionException::class.java)
    }

    @Test
    fun `결제 전 취소는 환불 대상이 아니다`() {
        val o = order().apply { cancel(CancelReason.CUSTOMER_CANCELLED, now) }
        assertThat(o.wasPaid).isFalse()
        assertThat(o.status).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `Saga - 포인트가 있으면 포인트 차감부터, 전액 포인트면 카드 결제를 건너뛴다`() {
        val saga = CheckoutSaga.start(UUID.randomUUID(), Money(5000), Money(0), "", now, now.plusSeconds(120))
        assertThat(saga.firstStep()).isEqualTo(CheckoutSaga.Next.RedeemPoints)
        assertThat(saga.onPointsRedeemed()).isEqualTo(CheckoutSaga.Next.Complete)
    }

    @Test
    fun `Saga - 단계에 맞지 않는 응답(중복·역순)은 거부된다`() {
        val saga = CheckoutSaga.start(UUID.randomUUID(), Money(0), Money(5000), "tok", now, now.plusSeconds(120))
        assertThat(saga.firstStep()).isEqualTo(CheckoutSaga.Next.ChargeCard)
        assertThatThrownBy { saga.onPointsRedeemed() }.isInstanceOf(SagaOutOfOrderException::class.java)
    }
}
