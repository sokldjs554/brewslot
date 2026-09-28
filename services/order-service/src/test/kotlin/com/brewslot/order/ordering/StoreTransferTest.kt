package com.brewslot.order.ordering

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.CapacityProfile
import com.brewslot.order.catalog.domain.ChosenDrink
import com.brewslot.order.catalog.domain.MenuEquivalence
import com.brewslot.order.catalog.domain.MenuItem
import com.brewslot.order.catalog.domain.MenuMatch
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.ordering.domain.IllegalOrderTransitionException
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.domain.StoreTransfer
import com.brewslot.order.ordering.domain.TransferClosedException
import com.brewslot.order.ordering.domain.TransferFailReason
import com.brewslot.order.ordering.domain.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

class StoreTransferTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val latte = OrderLine(1, 1003, "카페라떼(HOT)", Money(4500), 2, Station.ESPRESSO, 2, 5)
    private val latteAt103 = OrderLine(1, 4003, "카페라떼(HOT)", Money(4500), 2, Station.ESPRESSO, 2, 5)

    private fun paidOrder(): Order = Order.place(
        UUID.randomUUID(), 1, 101, 1, listOf(latte), now.plusSeconds(1800), Money(1000), now.plusSeconds(300), now,
    ).apply {
        startCheckout(now)
        markPaid(now)
    }

    private fun store(vararg menu: MenuItem) = Store(
        103, 1, "삼성점", LocalTime.MIN, LocalTime.of(23, 59),
        CapacityProfile(5, mapOf(Station.ESPRESSO to 8), 5, 1440), menu.toList(),
    )

    private fun item(id: Long, name: String, price: Long, available: Boolean = true) =
        MenuItem(id, 103, name, Money(price), Station.ESPRESSO, 2, 5, available)

    @Test
    fun `같은 이름 · 같은 가격 · 판매 중일 때만 옮길 수 있고, 안 되는 이유를 음료 이름으로 알려준다`() {
        val drinks = listOf(ChosenDrink("카페라떼(HOT)", Money(4500), 2), ChosenDrink("딸기 스무디", Money(6000), 1))
        val ok = MenuEquivalence.match(drinks.take(1), store(item(4003, "카페라떼(HOT)", 4500)))
        assertThat((ok as MenuMatch.Compatible).items.single().let { it.first.id to it.second }).isEqualTo(4003L to 2)

        val bad = MenuEquivalence.match(drinks, store(item(4003, "카페라떼(HOT)", 4800))) as MenuMatch.Incompatible
        assertThat(bad.notSold).containsExactly("딸기 스무디")
        assertThat(bad.priceDiffers).containsExactly("카페라떼(HOT)")
        assertThat(bad.reason).isEqualTo("NOT_SOLD")

        val soldOut = MenuEquivalence.match(drinks.take(1), store(item(4003, "카페라떼(HOT)", 4500, available = false)))
        assertThat((soldOut as MenuMatch.Incompatible).reason).isEqualTo("SOLD_OUT")
    }

    @Test
    fun `결제된 주문만 옮길 수 있고, 금액이 달라지는 변경은 막는다`() {
        val placed = Order.place(UUID.randomUUID(), 1, 101, 1, listOf(latte), now.plusSeconds(1800), Money(0), now.plusSeconds(300), now)
        assertThatThrownBy { StoreTransfer.request(UUID.randomUUID(), placed, 103, now, listOf(latteAt103), now, now.plusSeconds(120)) }
            .isInstanceOf(IllegalOrderTransitionException::class.java)

        val paid = paidOrder()
        assertThatThrownBy { paid.transferTo(103, listOf(latteAt103.copy(unitPrice = Money(4800))), now.plusSeconds(2100)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        paid.transferTo(103, listOf(latteAt103), now.plusSeconds(2100))
        assertThat(paid.storeId).isEqualTo(103)
        assertThat(paid.lines.single().menuItemId).isEqualTo(4003)
        assertThat(paid.cardAmount).isEqualTo(Money(8000)) // 결제 금액은 그대로 (포인트 1,000 + 카드 8,000)
        assertThat(paid.status).isEqualTo(OrderStatus.PAID)

        paid.accept(now)
        assertThatThrownBy { paid.transferTo(101, listOf(latte), now) }.isInstanceOf(IllegalOrderTransitionException::class.java)
    }

    @Test
    fun `변경 요청은 한 번만 닫힌다 - 수락 · 거절 · 만료 · 중단 중 먼저 온 하나`() {
        val t = StoreTransfer.request(UUID.randomUUID(), paidOrder(), 103, now.plusSeconds(1800), listOf(latteAt103), now, now.plusSeconds(120))
        assertThat(t.isOverdue(now.plusSeconds(119))).isFalse()
        assertThat(t.isOverdue(now.plusSeconds(120))).isTrue()

        t.abort(TransferFailReason.ORIGINAL_STORE_STARTED, now)
        assertThat(t.status).isEqualTo(TransferStatus.ABORTED)
        assertThat(t.isOverdue(now.plusSeconds(999))).isFalse()
        assertThatThrownBy { t.complete(now) }.isInstanceOf(TransferClosedException::class.java)
        assertThatThrownBy { t.refuse(now) }.isInstanceOf(TransferClosedException::class.java)
    }
}
