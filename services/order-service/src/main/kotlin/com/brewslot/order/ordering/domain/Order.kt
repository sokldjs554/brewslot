package com.brewslot.order.ordering.domain

import com.brewslot.common.Money
import com.brewslot.common.sum
import com.brewslot.order.catalog.domain.Station
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    /** 슬롯 임시 점유(hold) 상태. holdExpiresAt 까지 결제를 시작하지 않으면 만료된다. */
    PENDING_PAYMENT,

    /** 결제 Saga 진행 중 (포인트 차감 → 카드 결제) */
    PAYMENT_IN_PROGRESS,
    PAID,
    PREPARING,
    READY,
    PICKED_UP,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == PICKED_UP || this == CANCELLED
}

enum class CancelReason(val refundRequired: Boolean) {
    HOLD_EXPIRED(false),
    POINTS_INSUFFICIENT(false),

    /** 쿠폰이 이미 사용·만료됐거나 할인액이 주문 시 금액과 다르다 */
    COUPON_REJECTED(false),
    PAYMENT_DECLINED(false),
    PAYMENT_TIMEOUT(false),
    CUSTOMER_CANCELLED(true),
    REJECTED_BY_STORE(true),
}

data class OrderLine(
    val lineNo: Int,
    val menuItemId: Long,
    val name: String,
    val unitPrice: Money,
    val quantity: Int,
    val station: Station,
    val loadUnits: Int,
    val freshnessMinutes: Int,
) {
    init {
        require(quantity in 1..20) { "quantity must be 1..20" }
    }

    val amount: Money get() = unitPrice * quantity
}

class IllegalOrderTransitionException(val from: OrderStatus, val action: String) :
    RuntimeException("주문 상태 $from 에서는 '$action' 을(를) 할 수 없습니다.")

/**
 * 주문 Aggregate Root.
 *
 * 상태 전이는 전부 이 클래스의 메서드로만 일어나며, 허용되지 않은 전이는 예외로 막는다.
 * 슬롯 점유·결제·포인트 같은 다른 Aggregate/서비스와의 일관성은 애플리케이션 계층(Saga)이 맞춘다.
 */
class Order private constructor(
    val id: UUID,
    val memberId: Long,
    storeId: Long,
    val brandId: Long,
    lines: List<OrderLine>,
    promisedPickupAt: Instant,
    val pointAmount: Money,
    val holdExpiresAt: Instant,
    val createdAt: Instant,
    status: OrderStatus,
    paidAt: Instant?,
    preparingAt: Instant?,
    readyAt: Instant?,
    pickedUpAt: Instant?,
    cancelledAt: Instant?,
    cancelReason: CancelReason?,
    version: Long,
    /** 적용한 쿠폰. 할인액은 주문 시 고객이 본 금액이며 loyalty-service 가 결제 단계에서 실제 쿠폰과 대조한다. */
    val couponId: UUID?,
    val couponAmount: Money,
) {
    /** 낙관적 잠금 버전. 저장소가 UPDATE 성공 시 올린다. */
    var version: Long = version
        internal set

    /** 제조 · 픽업 매장. 결제 후 매장 변경([transferTo])으로 한 번 바뀔 수 있다. */
    var storeId: Long = storeId
        private set
    var lines: List<OrderLine> = lines
        private set
    var promisedPickupAt: Instant = promisedPickupAt
        private set

    var status: OrderStatus = status
        private set
    var paidAt: Instant? = paidAt
        private set
    var preparingAt: Instant? = preparingAt
        private set
    var readyAt: Instant? = readyAt
        private set
    var pickedUpAt: Instant? = pickedUpAt
        private set
    var cancelledAt: Instant? = cancelledAt
        private set
    var cancelReason: CancelReason? = cancelReason
        private set

    val totalAmount: Money get() = lines.map { it.amount }.sum()
    val cardAmount: Money get() = totalAmount - couponAmount - pointAmount

    /** 포인트·쿠폰처럼 loyalty-service 가 처리하는 결제 수단이 있는가 (Saga 1단계 필요 여부) */
    val hasBenefits: Boolean get() = pointAmount.isPositive || couponAmount.isPositive

    /** 결제가 완료되어 돈(카드/포인트)이 실제로 움직인 상태였는가 */
    val wasPaid: Boolean get() = paidAt != null

    fun startCheckout(now: Instant) {
        requireStatus("결제 시작", OrderStatus.PENDING_PAYMENT)
        if (!now.isBefore(holdExpiresAt)) throw IllegalOrderTransitionException(status, "만료된 주문 결제")
        status = OrderStatus.PAYMENT_IN_PROGRESS
    }

    fun markPaid(now: Instant) {
        requireStatus("결제 완료", OrderStatus.PAYMENT_IN_PROGRESS)
        status = OrderStatus.PAID
        paidAt = now
    }

    fun accept(now: Instant) {
        requireStatus("제조 시작", OrderStatus.PAID)
        status = OrderStatus.PREPARING
        preparingAt = now
    }

    fun markReady(now: Instant) {
        requireStatus("제조 완료", OrderStatus.PREPARING)
        status = OrderStatus.READY
        readyAt = now
    }

    fun handOff(now: Instant) {
        requireStatus("픽업 완료", OrderStatus.READY)
        status = OrderStatus.PICKED_UP
        pickedUpAt = now
    }

    /**
     * 결제된 주문을 같은 브랜드의 다른 매장으로 옮긴다. 결제 금액(카드·포인트·쿠폰)은 그대로이므로
     * 새 매장의 항목 합계가 원래 합계와 같아야 한다. 제조가 시작되면(PREPARING) 옮길 수 없다.
     */
    fun transferTo(toStoreId: Long, newLines: List<OrderLine>, newPickupAt: Instant) {
        requireStatus("매장 변경", OrderStatus.PAID)
        require(toStoreId != storeId) { "같은 매장으로는 변경할 수 없습니다." }
        require(newLines.isNotEmpty()) { "주문 항목이 비어 있습니다." }
        require(newLines.map { it.amount }.sum() == totalAmount) { "매장 변경 후 금액이 결제 금액과 다릅니다." }
        storeId = toStoreId
        lines = newLines
        promisedPickupAt = newPickupAt
    }

    fun cancel(reason: CancelReason, now: Instant) {
        val allowedFrom = when (reason) {
            CancelReason.HOLD_EXPIRED -> setOf(OrderStatus.PENDING_PAYMENT)
            CancelReason.POINTS_INSUFFICIENT, CancelReason.COUPON_REJECTED, CancelReason.PAYMENT_DECLINED, CancelReason.PAYMENT_TIMEOUT ->
                setOf(OrderStatus.PAYMENT_IN_PROGRESS)
            // 제조가 시작되면 고객 취소 불가 (이미 원가가 발생)
            CancelReason.CUSTOMER_CANCELLED -> setOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)
            CancelReason.REJECTED_BY_STORE -> setOf(OrderStatus.PAID)
        }
        requireStatus("취소($reason)", *allowedFrom.toTypedArray())
        status = OrderStatus.CANCELLED
        cancelledAt = now
        cancelReason = reason
    }

    /** 픽업 약속 대비 지연(음수면 일찍 준비됨). READY 이후에만 의미가 있다. */
    fun latenessSeconds(): Long? = readyAt?.let { it.epochSecond - promisedPickupAt.epochSecond }

    private fun requireStatus(action: String, vararg allowed: OrderStatus) {
        if (status !in allowed) throw IllegalOrderTransitionException(status, action)
    }

    companion object {
        fun place(
            id: UUID,
            memberId: Long,
            storeId: Long,
            brandId: Long,
            lines: List<OrderLine>,
            promisedPickupAt: Instant,
            pointAmount: Money,
            holdExpiresAt: Instant,
            now: Instant,
            couponId: UUID? = null,
            couponAmount: Money = Money.ZERO,
        ): Order {
            require(lines.isNotEmpty()) { "주문 항목이 비어 있습니다." }
            require(!pointAmount.isNegative) { "사용 포인트는 0 이상이어야 합니다." }
            require(!couponAmount.isNegative) { "쿠폰 할인액은 0 이상이어야 합니다." }
            require((couponId == null) == (couponAmount == Money.ZERO)) { "쿠폰과 할인액은 함께 지정해야 합니다." }
            val order = Order(
                id,
                memberId,
                storeId,
                brandId,
                lines,
                promisedPickupAt,
                pointAmount,
                holdExpiresAt,
                now,
                OrderStatus.PENDING_PAYMENT,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                couponId,
                couponAmount,
            )
            require(couponAmount <= order.totalAmount) { "쿠폰 할인액이 주문 금액을 초과합니다." }
            require(pointAmount <= order.totalAmount - couponAmount) { "사용 포인트가 할인 후 금액을 초과합니다." }
            return order
        }

        fun reconstitute(
            id: UUID,
            memberId: Long,
            storeId: Long,
            brandId: Long,
            lines: List<OrderLine>,
            promisedPickupAt: Instant,
            pointAmount: Money,
            holdExpiresAt: Instant,
            createdAt: Instant,
            status: OrderStatus,
            paidAt: Instant?,
            preparingAt: Instant?,
            readyAt: Instant?,
            pickedUpAt: Instant?,
            cancelledAt: Instant?,
            cancelReason: CancelReason?,
            version: Long,
            couponId: UUID? = null,
            couponAmount: Money = Money.ZERO,
        ) = Order(
            id,
            memberId,
            storeId,
            brandId,
            lines,
            promisedPickupAt,
            pointAmount,
            holdExpiresAt,
            createdAt,
            status,
            paidAt,
            preparingAt,
            readyAt,
            pickedUpAt,
            cancelledAt,
            cancelReason,
            version,
            couponId,
            couponAmount,
        )
    }
}
