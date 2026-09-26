package com.brewslot.order.query

import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.ordering.application.PlaceOrderCommand
import com.brewslot.order.ordering.application.PlaceOrderResult
import com.brewslot.order.ordering.application.PlaceOrderService
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.scheduling.application.CartItem
import com.brewslot.order.scheduling.application.PickupAvailabilityService
import com.brewslot.web.BusinessException
import com.brewslot.web.NotFoundException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class ReorderSuggestion(
    val sourceOrderId: String,
    val storeId: Long,
    val storeName: String,
    val items: List<SuggestedItem>,
    /** 현재 가격 기준 합계 (지난 주문 이후 가격이 바뀌었을 수 있다) */
    val currentTotalAmount: Long,
    val lastOrderedTotalAmount: Long,
    val orderable: Boolean,
    val unavailableItems: List<String>,
    /** 지금 주문하면 받을 수 있는 가장 빠른 픽업 시각 */
    val earliestPickupAt: Instant?,
)

data class SuggestedItem(val menuItemId: Long, val name: String, val quantity: Int, val currentUnitPrice: Long?)

/**
 * "3번 클릭 재주문" — 재주문율이 높은 서비스에서 가장 자주 쓰이는 경로.
 * 지난 장바구니를 그대로 보여주는 것만으로는 부족하다: 품절·가격 변경·지금 가능한 픽업 시각까지 미리 계산해
 * 사용자가 누르는 순간 실패하지 않도록 한다.
 */
@Service
class ReorderService(
    private val query: MemberOrderQuery,
    private val catalog: CatalogService,
    private val availability: PickupAvailabilityService,
    private val placeOrder: PlaceOrderService,
    private val orders: JdbcOrderRepository,
) {
    fun suggestions(memberId: Long, limit: Int = 3): List<ReorderSuggestion> =
        query.recentDistinctCarts(memberId, limit).map { past ->
            val store = catalog.store(past.storeId)
            val items = past.items.map { item ->
                SuggestedItem(
                    item.menuItemId,
                    item.name,
                    item.quantity,
                    store.menuItem(item.menuItemId)?.takeIf {
                        it.available
                    }?.price?.won,
                )
            }
            val unavailable = items.filter { it.currentUnitPrice == null }.map { it.name }
            val orderable = unavailable.isEmpty()
            val earliest = if (orderable) {
                availability.options(store.id, items.map { CartItem(it.menuItemId, it.quantity) }, null, null)
                    .firstOrNull { it.feasible }?.pickupAt
            } else {
                null
            }
            ReorderSuggestion(
                sourceOrderId = past.orderId,
                storeId = store.id,
                storeName = store.name,
                items = items,
                currentTotalAmount = items.sumOf { (it.currentUnitPrice ?: 0) * it.quantity },
                lastOrderedTotalAmount = past.totalAmount,
                orderable = orderable,
                unavailableItems = unavailable,
                earliestPickupAt = earliest,
            )
        }

    fun reorder(memberId: Long, sourceOrderId: UUID, pickupAt: Instant, pointsToUse: Long, idempotencyKey: String): PlaceOrderResult {
        val source = orders.findById(sourceOrderId)?.takeIf { it.memberId == memberId }
            ?: throw NotFoundException("order", sourceOrderId)
        if (source.status.name != "PICKED_UP") {
            throw BusinessException("reorder-source-not-completed", org.springframework.http.HttpStatus.CONFLICT, "완료된 주문만 재주문할 수 있습니다.")
        }
        return placeOrder.place(
            PlaceOrderCommand(
                memberId = memberId,
                storeId = source.storeId,
                pickupAt = pickupAt,
                items = source.lines.map { CartItem(it.menuItemId, it.quantity) },
                pointsToUse = pointsToUse,
                idempotencyKey = idempotencyKey,
            ),
        )
    }
}
