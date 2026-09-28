package com.brewslot.order.ordering.application

import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.ChosenDrink
import com.brewslot.order.catalog.domain.MenuEquivalence
import com.brewslot.order.catalog.domain.MenuMatch
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.scheduling.application.CartItem
import com.brewslot.order.scheduling.application.PickupAvailabilityService
import com.brewslot.order.scheduling.application.SlotReservationService
import com.brewslot.order.scheduling.domain.BrewScheduler
import com.brewslot.order.scheduling.domain.DrinkDemand
import com.brewslot.order.scheduling.domain.ScheduleResult
import com.brewslot.order.scheduling.infra.RedisSlotUsageCache
import org.springframework.stereotype.Component
import java.time.Instant

/** 다른 매장 한 곳에 대한 평가: 같은 음료를 팔고, 원하는 시각(또는 가까운 시각)에 만들 수 있는가 */
data class StoreEvaluation(
    val store: Store,
    val match: MenuMatch,
    /** 원하는 시각 그대로 가능한가 */
    val requestedTimeFeasible: Boolean,
    /** 원하는 시각에 가장 가까운 가능 시각 (요청 시각 제외, 최대 3개) */
    val nearestTimes: List<Instant>,
) {
    val cart: List<CartItem>
        get() = (match as? MenuMatch.Compatible)?.items?.map { (m, q) -> CartItem(m.id, q) } ?: emptyList()
}

/**
 * "이 매장에서 안 되면 같은 브랜드의 다른 매장은?" 을 계산한다.
 * 주문 시 409(원하는 시각 불가)의 근처 매장 제안과, 결제 후 매장 변경 후보 조회가 같은 규칙을 쓴다.
 */
@Component
class StoreAlternatives(
    private val catalog: CatalogService,
    private val slots: SlotReservationService,
    private val cache: RedisSlotUsageCache,
) {
    fun evaluateOthers(source: Store, drinks: List<ChosenDrink>, requested: Instant, now: Instant): List<StoreEvaluation> =
        catalog.storeIdsOfBrand(source.brandId)
            .filter { it != source.id }
            .map { evaluate(catalog.store(it), drinks, requested, now) }

    fun evaluate(target: Store, drinks: List<ChosenDrink>, requested: Instant, now: Instant): StoreEvaluation {
        val match = MenuEquivalence.match(drinks, target)
        if (match !is MenuMatch.Compatible) return StoreEvaluation(target, match, false, emptyList())
        val demands = PickupAvailabilityService.demandsOf(target, match.items.map { (m, q) -> CartItem(m.id, q) })
        return StoreEvaluation(
            target,
            match,
            requestedTimeFeasible = feasibleAt(target, demands, requested, now),
            nearestTimes = slots.alternatives(target, requested, demands, now),
        )
    }

    /** 매장 변경 대상 시각이 그 매장의 주문 가능 범위이고, 현재 용량으로 만들 수 있는가 (Redis 스냅샷, 판단만 — 확정은 예약 트랜잭션) */
    private fun feasibleAt(store: Store, demands: List<DrinkDemand>, at: Instant, now: Instant): Boolean {
        if (at !in slots.candidateTimes(store, now, at, at)) return false
        val grid = slots.gridOf(store)
        val window = SlotReservationService.windowFor(at, demands, grid.slotMinutes)
        val snapshot = cache.snapshot(store.id, store.capacity, window.first, window.second)
        return BrewScheduler(grid).schedule(at, demands, snapshot, slots.earliestSlotStart(store, now)) is ScheduleResult.Feasible
    }
}
