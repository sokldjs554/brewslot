package com.brewslot.order.catalog.domain

import com.brewslot.common.Money
import java.time.LocalTime

/**
 * 음료를 만드는 "작업대". 스테이션마다 슬롯당 처리 가능한 부하(load unit)가 다르다.
 * 에스프레소 머신이 붐벼도 블렌더는 비어 있을 수 있으므로, 주문 수가 아니라 스테이션별 부하로 용량을 본다.
 */
enum class Station {
    ESPRESSO,
    BLENDER,
    BREW_BAR,
}

/**
 * @property loadUnits 이 메뉴 한 잔이 해당 스테이션 슬롯에서 차지하는 부하 (아메리카노 1, 라떼 2, 스무디 3 ...)
 * @property freshnessMinutes 완성 후 픽업까지 품질이 유지되는 최대 시간. 이보다 일찍 만들 수 없다.
 *   (아이스 음료의 얼음이 녹거나, 핫 음료가 식는 것을 막기 위한 제약 — 백워드 스케줄링의 창(window) 크기)
 */
data class MenuItem(
    val id: Long,
    val storeId: Long,
    val name: String,
    val price: Money,
    val station: Station,
    val loadUnits: Int,
    val freshnessMinutes: Int,
    val available: Boolean,
) {
    init {
        require(loadUnits > 0) { "loadUnits must be positive" }
        require(freshnessMinutes > 0) { "freshnessMinutes must be positive" }
    }
}

/**
 * 매장의 제조 용량 설정.
 *
 * @property slotMinutes 스케줄링 격자 크기(분). 60 의 약수여야 한다.
 * @property unitsPerSlot 스테이션별 슬롯당 처리 가능 부하. (바리스타 수·머신 수를 반영해 점주가 설정)
 * @property minLeadMinutes 주문 시점부터 가장 이른 픽업까지 최소 시간.
 * @property maxAdvanceMinutes 얼마나 먼 미래까지 예약 주문을 받을지.
 */
data class CapacityProfile(
    val slotMinutes: Int,
    val unitsPerSlot: Map<Station, Int>,
    val minLeadMinutes: Int,
    val maxAdvanceMinutes: Int,
) {
    init {
        require(slotMinutes in 1..60 && 60 % slotMinutes == 0) { "slotMinutes must divide 60" }
        require(unitsPerSlot.values.all { it >= 0 }) { "unitsPerSlot must be >= 0" }
        require(minLeadMinutes >= 0 && maxAdvanceMinutes > minLeadMinutes) { "invalid lead/advance window" }
    }

    fun unitsOf(station: Station): Int = unitsPerSlot[station] ?: 0
}

data class Store(
    val id: Long,
    val brandId: Long,
    val name: String,
    val openTime: LocalTime,
    val closeTime: LocalTime,
    val capacity: CapacityProfile,
    val menu: List<MenuItem>,
    val location: GeoPoint? = null,
    /** 자리가 있으면 매장 변경 요청을 직원 확인 없이 바로 수락한다 */
    val autoAcceptTransfers: Boolean = false,
) {
    /** 이 매장에서 [other] 까지 도보 시간(분). 위치를 모르면 null */
    fun walkMinutesTo(other: Store): Int? = location?.let { a -> other.location?.let { a.walkMinutesTo(it) } }

    fun menuItem(id: Long): MenuItem? = menu.firstOrNull { it.id == id }
}
