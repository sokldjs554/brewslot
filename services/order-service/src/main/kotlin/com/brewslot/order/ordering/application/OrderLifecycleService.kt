package com.brewslot.order.ordering.application

import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.RefundPayment
import com.brewslot.messaging.contract.ReverseRedemption
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.order.ordering.domain.CancelReason
import com.brewslot.order.ordering.domain.IllegalOrderTransitionException
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.scheduling.application.SlotReservationService
import com.brewslot.web.ConflictException
import com.brewslot.web.NotFoundException
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 결제 이후의 주문 생애주기: 매장 접수/거절, 제조 완료, 픽업, 고객 취소 */
@Service
class OrderLifecycleService(
    private val orders: JdbcOrderRepository,
    private val slots: SlotReservationService,
    private val events: OrderEvents,
    private val outbox: Outbox,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional
    fun changeStatusByStore(storeId: Long, orderId: UUID, target: StoreAction): Order {
        val order = load(orderId).takeIf { it.storeId == storeId } ?: throw NotFoundException("order", orderId)
        val now = clock.instant()
        transition {
            when (target) {
                StoreAction.PREPARING -> order.accept(now).also { events.preparing(order) }
                StoreAction.READY -> order.markReady(now).also {
                    events.ready(order)
                    recordLateness(order)
                }
                StoreAction.PICKED_UP -> order.handOff(now).also { events.pickedUp(order) }
                StoreAction.REJECTED -> cancelWithCompensation(order, CancelReason.REJECTED_BY_STORE)
            }
        }
        orders.update(order)
        return order
    }

    @Transactional
    fun cancelByCustomer(memberId: Long, orderId: UUID): Order {
        val order = load(orderId).takeIf { it.memberId == memberId } ?: throw NotFoundException("order", orderId)
        if (order.status == OrderStatus.CANCELLED) return order // 멱등
        transition { cancelWithCompensation(order, CancelReason.CUSTOMER_CANCELLED) }
        orders.update(order)
        return order
    }

    /**
     * 결제가 끝난 주문의 취소는 "명령을 확실히 전달" 하는 것으로 끝낸다(Outbox).
     * 환불/포인트 복원의 완료를 기다리지 않으며, 수신 측은 멱등하게 처리하고 실패 시 DLT 로 격리되어 운영자가 개입한다.
     */
    private fun cancelWithCompensation(order: Order, reason: CancelReason) {
        val now = clock.instant()
        order.cancel(reason, now)
        if (order.wasPaid) {
            if (order.cardAmount.isPositive) {
                outbox.publish(Topics.PAYMENT_COMMANDS, order.id.toString(), RefundPayment(order.id.toString(), reason.name))
            }
            if (order.pointAmount.isPositive) {
                outbox.publish(Topics.LOYALTY_COMMANDS, order.id.toString(), ReverseRedemption(order.id.toString(), reason.name))
            }
        }
        slots.release(order.storeId, order.id, now)
        events.cancelled(order)
    }

    private fun recordLateness(order: Order) {
        val lateness = order.latenessSeconds() ?: return
        DistributionSummary.builder("brewslot.pickup.promise.lateness")
            .description("약속한 픽업 시각 대비 제조 완료 지연(초). 음수는 여유 있게 완료.")
            .baseUnit("seconds")
            .tag("store", order.storeId.toString())
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(meterRegistry)
            .record(lateness.toDouble())
    }

    private fun load(orderId: UUID) = orders.findByIdForUpdate(orderId) ?: throw NotFoundException("order", orderId)

    private inline fun transition(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalOrderTransitionException) {
            throw ConflictException("illegal-order-transition", e.message!!, mapOf("currentStatus" to e.from.name))
        }
    }
}

enum class StoreAction { PREPARING, READY, PICKED_UP, REJECTED }
