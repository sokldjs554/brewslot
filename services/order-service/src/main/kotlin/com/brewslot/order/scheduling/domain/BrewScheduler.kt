package com.brewslot.order.scheduling.domain

import com.brewslot.order.catalog.domain.Station
import java.time.Instant

/** 한 잔 단위의 제조 수요. */
data class DrinkDemand(
    val menuItemId: Long,
    val station: Station,
    val loadUnits: Int,
    val freshnessMinutes: Int,
)

data class Allocation(val station: Station, val slotStart: Instant, val units: Int) {
    val key: SlotKey get() = SlotKey(station, slotStart)
}

/** 주문 하나를 어느 스테이션의 어느 슬롯에서 만들지에 대한 계획. 동일 슬롯 할당은 합쳐져 있다. */
data class ProductionPlan(val allocations: List<Allocation>) {
    val totalUnits: Int get() = allocations.sumOf { it.units }

    /** 락 획득 순서. 모든 트랜잭션이 같은 순서로 슬롯 행을 잠가야 교착상태가 생기지 않는다. */
    fun inLockOrder(): List<Allocation> = allocations.sortedWith(compareBy({ it.station.ordinal }, { it.slotStart }))
}

sealed interface ScheduleResult {
    data class Feasible(val plan: ProductionPlan) : ScheduleResult

    data class Infeasible(val bottleneck: Station, val reason: Reason) : ScheduleResult

    enum class Reason {
        /** 신선도 창 안의 슬롯이 모두 찼다 */
        CAPACITY_EXHAUSTED,

        /** 신선도 창이 이미 지나갔다 (픽업 시각이 너무 임박) */
        NO_PRODUCTION_WINDOW,

        /** 음료 한 잔의 부하가 슬롯 용량보다 크다 (매장 설정 오류) */
        EXCEEDS_SLOT_CAPACITY,
    }
}

/**
 * 픽업 시각으로부터 거꾸로(backward) 제조 슬롯을 배정하는 스케줄러.
 *
 * 흔한 "시간대별 주문 N건 제한" 방식과의 차이:
 *  1. 주문 건수가 아니라 스테이션별 제조 부하로 용량을 본다. (아메리카노 10잔 ≠ 스무디 10잔)
 *  2. 한 주문의 음료들을 서로 다른 슬롯에 나눠 배정할 수 있다. (라떼는 5분 전, 스무디는 10분 전)
 *  3. 음료마다 신선도 창이 있어, 한가한 새벽 슬롯에 미리 만들어 두는 식의 비현실적 배정을 하지 않는다.
 *
 * 알고리즘: 제약이 빡빡한 음료(신선도 창이 짧고 부하가 큰 것)부터, 창 안에서 "가장 늦은" 빈 슬롯에 배정한다.
 * 늦게 만들수록 품질이 좋고, 앞쪽 슬롯을 다음 주문들을 위해 남겨 두는 JIT 전략이다.
 * 결정적(deterministic)이므로 같은 입력에 항상 같은 계획을 만든다.
 *
 * 순수 함수로, 스프링/DB 에 의존하지 않는다. 동시성은 [ProductionPlan] 을 DB 에 반영하는 쪽이 책임진다.
 */
class BrewScheduler(private val grid: SlotGrid) {
    fun schedule(
        pickupAt: Instant,
        demands: List<DrinkDemand>,
        usage: SlotUsageSnapshot,
        earliestSlotStart: Instant,
    ): ScheduleResult {
        require(grid.isAligned(pickupAt)) { "pickupAt must be aligned to ${grid.slotMinutes}-minute slots" }
        require(demands.isNotEmpty()) { "demands must not be empty" }

        val tentative = HashMap<SlotKey, Int>()
        val ordered = demands.sortedWith(compareBy<DrinkDemand> { it.freshnessMinutes }.thenByDescending { it.loadUnits })

        for (drink in ordered) {
            val slots = grid.productionSlots(pickupAt, drink.freshnessMinutes, earliestSlotStart)
            if (slots.isEmpty()) {
                return ScheduleResult.Infeasible(drink.station, ScheduleResult.Reason.NO_PRODUCTION_WINDOW)
            }
            val chosen = slots.firstOrNull { slotStart ->
                val key = SlotKey(drink.station, slotStart)
                usage.remaining(key) - (tentative[key] ?: 0) >= drink.loadUnits
            }
            if (chosen == null) {
                val maxCapacity = slots.maxOf { usage.stateOf(SlotKey(drink.station, it)).capacity }
                val reason = if (drink.loadUnits > maxCapacity) {
                    ScheduleResult.Reason.EXCEEDS_SLOT_CAPACITY
                } else {
                    ScheduleResult.Reason.CAPACITY_EXHAUSTED
                }
                return ScheduleResult.Infeasible(drink.station, reason)
            }
            tentative.merge(SlotKey(drink.station, chosen), drink.loadUnits, Int::plus)
        }

        val allocations = tentative.map { (key, units) -> Allocation(key.station, key.slotStart, units) }
        return ScheduleResult.Feasible(ProductionPlan(allocations).let { ProductionPlan(it.inLockOrder()) })
    }
}
