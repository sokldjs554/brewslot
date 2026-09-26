package com.brewslot.order.catalog.api

import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.scheduling.application.CartItem
import com.brewslot.order.scheduling.application.PickupAvailabilityService
import com.brewslot.order.scheduling.domain.PickupOption
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalTime

data class MenuItemResponse(
    val menuItemId: Long,
    val name: String,
    val price: Long,
    val station: Station,
    val loadUnits: Int,
    val freshnessMinutes: Int,
    val available: Boolean,
)

data class StoreResponse(
    val storeId: Long,
    val brandId: Long,
    val name: String,
    val openTime: LocalTime,
    val closeTime: LocalTime,
    val slotMinutes: Int,
    val unitsPerSlot: Map<Station, Int>,
    val menu: List<MenuItemResponse>,
) {
    companion object {
        fun of(s: Store) = StoreResponse(
            s.id,
            s.brandId,
            s.name,
            s.openTime,
            s.closeTime,
            s.capacity.slotMinutes,
            s.capacity.unitsPerSlot,
            s.menu.map { MenuItemResponse(it.id, it.name, it.price.won, it.station, it.loadUnits, it.freshnessMinutes, it.available) },
        )
    }
}

data class CapacityProfileRequest(val unitsPerSlot: Map<Station, Int>)

@Tag(name = "Stores")
@RestController
class StoreController(
    private val catalog: CatalogService,
    private val availability: PickupAvailabilityService,
) {
    @Operation(summary = "매장 정보와 메뉴")
    @GetMapping("/stores/{storeId}")
    fun store(
        @PathVariable storeId: Long,
    ): StoreResponse = StoreResponse.of(catalog.store(storeId))

    @Operation(summary = "스테이션별 슬롯당 제조 용량 변경 (이미 받은 예약보다 작게 줄지 않음)")
    @PutMapping("/stores/{storeId}/capacity-profile")
    fun updateCapacity(
        @PathVariable storeId: Long,
        @RequestBody request: CapacityProfileRequest,
    ): StoreResponse {
        require(request.unitsPerSlot.values.all { it in 0..1000 }) { "unitsPerSlot must be 0..1000" }
        return StoreResponse.of(catalog.updateStationCapacity(storeId, request.unitsPerSlot))
    }

    @Operation(summary = "장바구니 기준 픽업 가능 시각과 혼잡도")
    @GetMapping("/stores/{storeId}/pickup-times")
    fun pickupTimes(
        @PathVariable storeId: Long,
        @Parameter(description = "menuItemId:quantity 목록. 예) items=1001:2&items=1005:1", required = true)
        @RequestParam items: List<String>,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): List<PickupOption> = availability.options(storeId, items.map(::parseItem), from, to)

    private fun parseItem(raw: String): CartItem {
        val parts = raw.split(':')
        require(parts.size == 2) { "items 형식은 menuItemId:quantity 입니다: $raw" }
        return CartItem(parts[0].trim().toLong(), parts[1].trim().toInt())
    }
}
