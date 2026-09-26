package com.brewslot.order.scheduling

import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.scheduling.domain.BrewScheduler
import com.brewslot.order.scheduling.domain.DrinkDemand
import com.brewslot.order.scheduling.domain.PickupTimeAdvisor
import com.brewslot.order.scheduling.domain.ScheduleResult
import com.brewslot.order.scheduling.domain.SlotGrid
import com.brewslot.order.scheduling.domain.SlotKey
import com.brewslot.order.scheduling.domain.SlotState
import com.brewslot.order.scheduling.domain.SlotUsageSnapshot
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/**
 * 공개 데모 페이지(site/)의 JavaScript 스케줄러가 서버와 "같은 입력 → 같은 결과" 를 내는지 검증하기 위한 테스트 벡터를 만든다.
 * 무작위 시나리오 500개를 실제 [BrewScheduler]/[PickupTimeAdvisor] 로 풀어 build/scheduler-vectors.json 에 쓰고,
 * CI 가 `node site/verify.mjs` 로 JS 구현과 대조한다.
 */
class SchedulerVectorExportTest {
    @Test
    fun `서버 스케줄러 결과를 테스트 벡터로 내보낸다`() {
        val random = Random(20260926)
        val grid = SlotGrid(5)
        val scheduler = BrewScheduler(grid)
        val advisor = PickupTimeAdvisor(grid, scheduler)
        val base = Instant.parse("2026-09-27T23:20:00Z").epochSecond // 08:20 KST
        val cases = (1..500).map {
            val capacity = Station.entries.associateWith { random.nextInt(0, 8) }
            val reserved = mutableMapOf<SlotKey, Int>()
            repeat(random.nextInt(0, 25)) {
                val station = Station.entries[random.nextInt(3)]
                val slot = Instant.ofEpochSecond(base + 300L * random.nextInt(0, 16))
                val cap = capacity.getValue(station)
                if (cap > 0) reserved[SlotKey(station, slot)] = random.nextInt(0, cap + 1)
            }
            val usage = SlotUsageSnapshot(reserved.mapValues { (k, r) -> SlotState(capacity.getValue(k.station), r) }) { capacity.getValue(it) }
            val demands = List(random.nextInt(1, 7)) {
                DrinkDemand(1000L + it, Station.entries[random.nextInt(3)], random.nextInt(1, 5), listOf(5, 10, 15).random(random))
            }
            val pickup = base + 300L * random.nextInt(1, 16)
            val earliest = base + 300L * random.nextInt(0, 3)
            val result = scheduler.schedule(Instant.ofEpochSecond(pickup), demands, usage, Instant.ofEpochSecond(earliest))
            val candidates = (1..15).map { Instant.ofEpochSecond(base + 300L * it) }
            val alternatives = advisor.nearestAlternatives(Instant.ofEpochSecond(pickup), candidates, demands, usage, Instant.ofEpochSecond(earliest))
            mapOf(
                "capacity" to capacity.mapKeys { it.key.name },
                "reserved" to reserved.map { (k, v) -> mapOf("station" to k.station.name, "slot" to k.slotStart.epochSecond, "units" to v) },
                "demands" to demands.map { mapOf("station" to it.station.name, "load" to it.loadUnits, "fresh" to it.freshnessMinutes) },
                "pickup" to pickup,
                "earliest" to earliest,
                "result" to when (result) {
                    is ScheduleResult.Feasible -> mapOf(
                        "feasible" to true,
                        "allocations" to result.plan.allocations.map { mapOf("station" to it.station.name, "slot" to it.slotStart.epochSecond, "units" to it.units) },
                    )
                    is ScheduleResult.Infeasible -> mapOf("feasible" to false, "bottleneck" to result.bottleneck.name, "reason" to result.reason.name)
                },
                "candidates" to candidates.map { it.epochSecond },
                "alternatives" to alternatives.map { it.epochSecond },
            )
        }
        val out = Path.of("build", "scheduler-vectors.json")
        Files.createDirectories(out.parent)
        ObjectMapper().writeValue(out.toFile(), cases)
        assertThat(cases.count { (it["result"] as Map<*, *>)["feasible"] == true }).isBetween(50, 450) // 양쪽 경로 모두 충분히 포함
    }
}
