package com.brewslot.order.scheduling.domain

import java.time.Duration
import java.time.Instant

/**
 * 시간을 고정 길이 슬롯으로 자르는 격자. 모든 슬롯 경계는 epoch 기준으로 정렬된다.
 * (KST 는 UTC+9 로 정시 단위 오프셋이므로 epoch 정렬 = 한국 시각 정렬)
 */
class SlotGrid(val slotMinutes: Int) {
    val slot: Duration = Duration.ofMinutes(slotMinutes.toLong())
    private val slotSeconds = slotMinutes * 60L

    fun isAligned(t: Instant): Boolean = t.nano == 0 && Math.floorMod(t.epochSecond, slotSeconds) == 0L

    fun floor(t: Instant): Instant = Instant.ofEpochSecond(t.epochSecond - Math.floorMod(t.epochSecond, slotSeconds))

    fun ceil(t: Instant): Instant {
        val f = floor(t)
        return if (f == t) f else f.plus(slot)
    }

    /**
     * 픽업 시각 [pickupAt] 에 맞추기 위해 음료를 만들 수 있는 슬롯들 (늦은 슬롯부터).
     *
     * 슬롯 s 에서 만든 음료는 s + slot 에 완성되므로 s + slot <= pickupAt 이어야 하고,
     * 완성 후 [freshnessMinutes] 를 넘기면 안 되므로 pickupAt - (s + slot) < freshness,
     * 즉 가장 이른 슬롯은 pickupAt - ceil(freshness / slot) * slot 이다. (최소 1개 슬롯은 항상 허용)
     */
    fun productionSlots(pickupAt: Instant, freshnessMinutes: Int, earliestSlotStart: Instant): List<Instant> {
        val depth = maxOf(1, (freshnessMinutes + slotMinutes - 1) / slotMinutes)
        return (1..depth)
            .map { pickupAt.minus(slot.multipliedBy(it.toLong())) }
            .filter { !it.isBefore(earliestSlotStart) }
    }
}
