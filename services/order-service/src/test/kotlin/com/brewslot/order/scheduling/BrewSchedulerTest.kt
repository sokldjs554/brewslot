package com.brewslot.order.scheduling

import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Station.BLENDER
import com.brewslot.order.catalog.domain.Station.ESPRESSO
import com.brewslot.order.scheduling.domain.Allocation
import com.brewslot.order.scheduling.domain.BrewScheduler
import com.brewslot.order.scheduling.domain.DrinkDemand
import com.brewslot.order.scheduling.domain.PickupTimeAdvisor
import com.brewslot.order.scheduling.domain.ScheduleResult
import com.brewslot.order.scheduling.domain.ScheduleResult.Reason
import com.brewslot.order.scheduling.domain.SlotGrid
import com.brewslot.order.scheduling.domain.SlotKey
import com.brewslot.order.scheduling.domain.SlotState
import com.brewslot.order.scheduling.domain.SlotUsageSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class BrewSchedulerTest {
    private val grid = SlotGrid(5)
    private val scheduler = BrewScheduler(grid)
    private val capacity = mapOf(ESPRESSO to 6, BLENDER to 3, Station.BREW_BAR to 2)
    private val pickup = Instant.parse("2026-09-28T00:00:00Z") // 09:00 KST
    private val earliest = Instant.parse("2026-09-27T23:00:00Z")

    private fun at(minutesBeforePickup: Long) = pickup.minusSeconds(minutesBeforePickup * 60)

    private fun usage(vararg reserved: Pair<SlotKey, Int>) =
        SlotUsageSnapshot(reserved.associate { (k, r) -> k to SlotState(capacity.getValue(k.station), r) }) { capacity.getValue(it) }

    private val hotAmericano = DrinkDemand(1001, ESPRESSO, 1, 5)
    private val hotLatte = DrinkDemand(1003, ESPRESSO, 2, 5)
    private val icedLatte = DrinkDemand(1004, ESPRESSO, 2, 10)
    private val smoothie = DrinkDemand(1005, BLENDER, 3, 10)

    private fun feasible(r: ScheduleResult) = (r as ScheduleResult.Feasible).plan

    @Nested
    @DisplayName("백워드 스케줄링")
    inner class BackwardScheduling {
        @Test
        fun `여유가 있으면 픽업 직전 슬롯(가장 늦은 슬롯)에 만든다`() {
            val plan = feasible(scheduler.schedule(pickup, listOf(hotLatte, hotAmericano), usage(), earliest))
            assertThat(plan.allocations).containsExactly(Allocation(ESPRESSO, at(5), 3))
        }

        @Test
        fun `직전 슬롯이 차면 신선도 창 안의 앞 슬롯으로 밀어낸다`() {
            val plan = feasible(scheduler.schedule(pickup, listOf(icedLatte), usage(SlotKey(ESPRESSO, at(5)) to 5), earliest))
            assertThat(plan.allocations).containsExactly(Allocation(ESPRESSO, at(10), 2))
        }

        @Test
        fun `신선도 창(핫 5분)을 넘어서는 미리 만들지 않는다 - 앞 슬롯이 비어 있어도 거절`() {
            val result = scheduler.schedule(pickup, listOf(hotLatte), usage(SlotKey(ESPRESSO, at(5)) to 5), earliest)
            assertThat(result).isEqualTo(ScheduleResult.Infeasible(ESPRESSO, Reason.CAPACITY_EXHAUSTED))
        }

        @Test
        fun `스테이션은 독립적이다 - 에스프레소가 가득 차도 스무디는 가능하다`() {
            val plan = feasible(
                scheduler.schedule(
                    pickup,
                    listOf(smoothie),
                    usage(
                        SlotKey(ESPRESSO, at(5)) to 6,
                        SlotKey(ESPRESSO, at(10)) to 6,
                    ),
                    earliest,
                ),
            )
            assertThat(plan.allocations).containsExactly(Allocation(BLENDER, at(5), 3))
        }

        @Test
        fun `한 주문의 음료를 여러 슬롯에 나눠 담는다`() {
            // 직전 슬롯 잔여 2 → 핫라떼(2, 창 5분)가 먼저 차지, 아이스라떼(2, 창 10분)는 앞 슬롯으로
            val plan = feasible(scheduler.schedule(pickup, listOf(icedLatte, hotLatte), usage(SlotKey(ESPRESSO, at(5)) to 4), earliest))
            assertThat(plan.allocations).containsExactlyInAnyOrder(
                Allocation(ESPRESSO, at(5), 2),
                Allocation(ESPRESSO, at(10), 2),
            )
        }

        @Test
        fun `제약이 빡빡한 음료를 먼저 배정해 불필요한 거절을 피한다`() {
            // 입력 순서가 아이스라떼 먼저여도, 창이 짧은 핫라떼가 직전 슬롯을 먼저 가져가야 둘 다 들어간다.
            val result = scheduler.schedule(pickup, listOf(icedLatte, hotLatte), usage(SlotKey(ESPRESSO, at(5)) to 4), earliest)
            assertThat(result).isInstanceOf(ScheduleResult.Feasible::class.java)
        }

        @Test
        fun `한 잔의 부하가 슬롯 용량보다 크면 설정 오류로 구분한다`() {
            val tooBig = DrinkDemand(9, BLENDER, 4, 10)
            assertThat(scheduler.schedule(pickup, listOf(tooBig), usage(), earliest))
                .isEqualTo(ScheduleResult.Infeasible(BLENDER, Reason.EXCEEDS_SLOT_CAPACITY))
        }

        @Test
        fun `이미 지나간 슬롯에는 배정하지 않는다`() {
            val result = scheduler.schedule(pickup, listOf(hotAmericano), usage(), earliestSlotStart = pickup)
            assertThat(result).isEqualTo(ScheduleResult.Infeasible(ESPRESSO, Reason.NO_PRODUCTION_WINDOW))
        }

        @Test
        fun `계획은 락 순서(스테이션, 시각)로 정렬되어 있다`() {
            val plan = feasible(
                scheduler.schedule(pickup, listOf(smoothie, icedLatte, hotLatte), usage(SlotKey(ESPRESSO, at(5)) to 4), earliest),
            )
            assertThat(plan.allocations).isEqualTo(plan.inLockOrder())
        }

        @Test
        fun `픽업 시각은 슬롯 경계여야 한다`() {
            assertThatThrownBy { scheduler.schedule(pickup.plusSeconds(60), listOf(hotAmericano), usage(), earliest) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("대안 시각 제안")
    inner class Alternatives {
        private val advisor = PickupTimeAdvisor(grid, scheduler)
        private val candidates = (-6..6).map { pickup.plusSeconds(it * 300L) }

        @Test
        fun `가장 가까운 가능 시각을 최대 3개, 시간순으로 제안한다`() {
            val full = usage(SlotKey(ESPRESSO, at(5)) to 6, SlotKey(ESPRESSO, at(10)) to 6)
            val alternatives = advisor.nearestAlternatives(pickup, candidates, listOf(hotLatte), full, earliest)
            // at(10) 슬롯도 가득 → 08:55 픽업 불가, 08:50(at(15) 슬롯 제조) 가능, 09:05·09:10 가능
            assertThat(alternatives).containsExactly(pickup.minusSeconds(600), pickup.plusSeconds(300), pickup.plusSeconds(600))
        }

        @Test
        fun `혼잡도는 픽업 직전 슬롯에서 가장 붐비는 스테이션 기준이다`() {
            val options = advisor.options(
                listOf(pickup),
                listOf(hotAmericano),
                usage(
                    SlotKey(ESPRESSO, at(5)) to 3,
                    SlotKey(BLENDER, at(5)) to 3,
                ),
                earliest,
            )
            assertThat(options.single().loadPercent).isEqualTo(100)
            assertThat(options.single().feasible).isTrue()
        }
    }
}
