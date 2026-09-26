package com.brewslot.order.scheduling.domain

import com.brewslot.order.catalog.domain.Station
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class PickupOption(
    val pickupAt: Instant,
    val feasible: Boolean,
    /** 픽업 직전 슬롯의 가장 붐비는 스테이션 사용률(0~100). 앱에서 "여유/보통/혼잡" 표시에 쓴다. */
    val loadPercent: Int,
)

/**
 * "원하는 시간에 픽업" 이 불가능할 때 거절로 끝내지 않고, 가능한 가장 가까운 시각을 제안한다.
 * 같은 거리면 더 늦은 시각을 먼저 제안한다(고객이 일찍 도착하는 것보다 조금 늦게 받는 쪽이 현실적으로 가능하다).
 */
class PickupTimeAdvisor(private val grid: SlotGrid, private val scheduler: BrewScheduler) {
    fun options(
        candidates: List<Instant>,
        demands: List<DrinkDemand>,
        usage: SlotUsageSnapshot,
        earliestSlotStart: Instant,
    ): List<PickupOption> = candidates.map { t ->
        val feasible = scheduler.schedule(t, demands, usage, earliestSlotStart) is ScheduleResult.Feasible
        PickupOption(t, feasible, loadPercentBefore(t, usage))
    }

    fun nearestAlternatives(
        requested: Instant,
        candidates: List<Instant>,
        demands: List<DrinkDemand>,
        usage: SlotUsageSnapshot,
        earliestSlotStart: Instant,
        limit: Int = 3,
    ): List<Instant> = candidates
        .filter { it != requested }
        .sortedWith(compareBy<Instant> { Duration.between(requested, it).abs() }.thenByDescending { it })
        .asSequence()
        .filter { scheduler.schedule(it, demands, usage, earliestSlotStart) is ScheduleResult.Feasible }
        .take(limit)
        .sorted()
        .toList()

    private fun loadPercentBefore(pickupAt: Instant, usage: SlotUsageSnapshot): Int {
        val slotStart = pickupAt.minus(grid.slot)
        return Station.entries.maxOf { station ->
            val state = usage.stateOf(SlotKey(station, slotStart))
            if (state.capacity == 0) 0 else (state.reserved * 100.0 / state.capacity).roundToInt()
        }
    }
}
