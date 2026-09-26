package com.brewslot.order.query

import com.brewslot.common.BusinessTime
import com.brewslot.web.BusinessException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Tag(name = "Queries (CQRS read side)")
@RestController
class QueryController(
    private val memberOrders: MemberOrderQuery,
    private val reorders: ReorderService,
    private val stores: StoreQueries,
    private val clock: Clock,
) {
    @Operation(summary = "내 주문 내역 (커서 페이지네이션, 최종적 일관성)")
    @GetMapping("/members/{memberId}/orders")
    fun orders(
        @RequestHeader("X-Member-Id") caller: Long,
        @PathVariable memberId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): OrderPage {
        requireSelf(caller, memberId)
        return memberOrders.page(memberId, cursor, size)
    }

    @Operation(summary = "원탭 재주문 추천 (품절·가격·가장 빠른 픽업 시각 재검증 포함)")
    @GetMapping("/members/{memberId}/reorder-suggestions")
    fun reorderSuggestions(
        @RequestHeader("X-Member-Id") caller: Long,
        @PathVariable memberId: Long,
    ): List<ReorderSuggestion> {
        requireSelf(caller, memberId)
        return reorders.suggestions(memberId)
    }

    @Operation(summary = "바리스타 제조 큐 (슬롯 순)")
    @GetMapping("/stores/{storeId}/production-queue")
    fun productionQueue(
        @PathVariable storeId: Long,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): List<ProductionSlot> {
        val start = from ?: clock.instant().minus(Duration.ofMinutes(15))
        return stores.productionQueue(storeId, start, to ?: start.plus(Duration.ofHours(2)))
    }

    @Operation(summary = "매장 일간 대시보드 (픽업 약속 준수율 등)")
    @GetMapping("/stores/{storeId}/dashboard")
    fun dashboard(
        @PathVariable storeId: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): StoreDashboard = stores.dashboard(storeId, date ?: BusinessTime.businessDateOf(clock.instant()))

    private fun requireSelf(caller: Long, memberId: Long) {
        if (caller != memberId) throw BusinessException("forbidden", HttpStatus.FORBIDDEN, "다른 회원의 정보는 조회할 수 없습니다.")
    }
}
