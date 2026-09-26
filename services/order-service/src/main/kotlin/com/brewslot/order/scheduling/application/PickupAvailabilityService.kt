package com.brewslot.order.scheduling.application

import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.scheduling.domain.BrewScheduler
import com.brewslot.order.scheduling.domain.DrinkDemand
import com.brewslot.order.scheduling.domain.PickupOption
import com.brewslot.order.scheduling.domain.PickupTimeAdvisor
import com.brewslot.order.scheduling.infra.RedisSlotUsageCache
import com.brewslot.web.UnprocessableException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class CartItem(val menuItemId: Long, val quantity: Int)

@Service
class PickupAvailabilityService(
    private val catalog: CatalogService,
    private val slots: SlotReservationService,
    private val cache: RedisSlotUsageCache,
    private val clock: Clock,
) {
    /** 장바구니 기준으로 각 픽업 후보 시각의 가능 여부와 혼잡도를 계산한다. (Redis 스냅샷 사용) */
    fun options(storeId: Long, cart: List<CartItem>, from: Instant?, to: Instant?): List<PickupOption> {
        val store = catalog.store(storeId)
        val now = clock.instant()
        val demands = demandsOf(store, cart)
        val candidates = slots.candidateTimes(store, now, from, to ?: now.plus(DEFAULT_HORIZON)).take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()

        val grid = slots.gridOf(store)
        val windowStart = SlotReservationService.windowFor(candidates.first(), demands, grid.slotMinutes).first
        val snapshot = cache.snapshot(store.id, store.capacity, windowStart, candidates.last())
        return PickupTimeAdvisor(grid, BrewScheduler(grid)).options(candidates, demands, snapshot, slots.earliestSlotStart(store, now))
    }

    companion object {
        private val DEFAULT_HORIZON: Duration = Duration.ofHours(2)
        private const val MAX_CANDIDATES = 72

        /** 장바구니를 "잔 단위" 제조 수요로 펼친다. */
        fun demandsOf(store: Store, cart: List<CartItem>): List<DrinkDemand> {
            if (cart.isEmpty()) throw UnprocessableException("empty-cart", "장바구니가 비어 있습니다.")
            return cart.flatMap { item ->
                val menu = store.menuItem(item.menuItemId)
                    ?: throw UnprocessableException("menu-item-not-found", "메뉴(${item.menuItemId})가 이 매장에 없습니다.")
                if (!menu.available) {
                    throw UnprocessableException(
                        "menu-item-unavailable",
                        "'${menu.name}' 은(는) 현재 주문할 수 없습니다.",
                        mapOf(
                            "menuItemId" to menu.id,
                        ),
                    )
                }
                require(item.quantity in 1..20) { "quantity must be 1..20" }
                List(item.quantity) { DrinkDemand(menu.id, menu.station, menu.loadUnits, menu.freshnessMinutes) }
            }
        }
    }
}
