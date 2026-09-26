package com.brewslot.order.scheduling.domain

import com.brewslot.order.catalog.domain.Station
import java.time.Instant

data class SlotKey(val station: Station, val slotStart: Instant)

data class SlotState(val capacity: Int, val reserved: Int) {
    val remaining: Int get() = capacity - reserved
}

/**
 * 특정 시점에 읽은 슬롯 사용량. 행이 아직 없는 슬롯은 "예약 0, 기본 용량"으로 본다.
 * (슬롯 행은 첫 예약 때 lazy 하게 생성된다)
 */
class SlotUsageSnapshot(
    private val states: Map<SlotKey, SlotState>,
    private val defaultCapacity: (Station) -> Int,
) {
    fun stateOf(key: SlotKey): SlotState = states[key] ?: SlotState(defaultCapacity(key.station), 0)

    fun remaining(key: SlotKey): Int = stateOf(key).remaining

    companion object {
        fun empty(defaultCapacity: (Station) -> Int) = SlotUsageSnapshot(emptyMap(), defaultCapacity)
    }
}
