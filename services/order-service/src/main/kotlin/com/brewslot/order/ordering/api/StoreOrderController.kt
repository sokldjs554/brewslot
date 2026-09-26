package com.brewslot.order.ordering.api

import com.brewslot.order.ordering.application.OrderLifecycleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 매장(점주·바리스타)용 주문 처리 API */
@Tag(name = "Store operations")
@RestController
class StoreOrderController(private val lifecycle: OrderLifecycleService) {
    @Operation(summary = "주문 상태 변경: PREPARING(접수) / READY(제조 완료) / PICKED_UP(전달) / REJECTED(거절·자동 환불)")
    @PutMapping("/stores/{storeId}/orders/{orderId}/status")
    fun changeStatus(
        @PathVariable storeId: Long,
        @PathVariable orderId: UUID,
        @RequestBody request: StoreStatusRequest,
    ): OrderResponse = OrderResponse.of(lifecycle.changeStatusByStore(storeId, orderId, request.status))
}
