package com.brewslot.order.ordering.saga

import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.ChargePayment
import com.brewslot.messaging.contract.RedeemPoints
import com.brewslot.messaging.contract.RefundPayment
import com.brewslot.messaging.contract.ReverseRedemption
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.order.config.OrderProperties
import com.brewslot.order.ordering.application.OrderEvents
import com.brewslot.order.ordering.domain.CancelReason
import com.brewslot.order.ordering.domain.IllegalOrderTransitionException
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.scheduling.application.SlotReservationService
import com.brewslot.web.BusinessException
import com.brewslot.web.ConflictException
import com.brewslot.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 결제 Saga 오케스트레이터.
 *
 * 모든 핸들러는 (1) 주문 행을 FOR UPDATE 로 잠그고 (2) Saga 상태를 확인한 뒤 (3) 상태 변경과 다음 명령 발행(Outbox)을
 * 같은 트랜잭션에 기록한다. 응답이 중복/지연/역순으로 와도 Saga 상태로 걸러지므로 결과가 한 번만 반영된다.
 *
 * 보상 명령(RefundPayment, ReverseRedemption)은 "했으면 되돌리고 안 했으면 무시" 하도록 수신 측이 멱등하게 구현되어 있어,
 * 결과를 모르는 상황(타임아웃)에서도 안전하게 보낼 수 있다. 같은 주문의 명령은 같은 파티션(key=orderId)으로 가므로
 * "차감 → 차감취소" 순서가 뒤집히지 않는다.
 */
@Service
class CheckoutSagaOrchestrator(
    private val orders: JdbcOrderRepository,
    private val sagas: JdbcCheckoutSagaRepository,
    private val slots: SlotReservationService,
    private val events: OrderEvents,
    private val outbox: Outbox,
    private val props: OrderProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun start(memberId: Long, orderId: UUID, cardToken: String): Order {
        val order = orders.findByIdForUpdate(orderId)?.takeIf { it.memberId == memberId }
            ?: throw NotFoundException("order", orderId)
        if (order.status != OrderStatus.PENDING_PAYMENT) {
            // 결제 요청 재전송(더블 클릭, 네트워크 재시도)은 같은 결과를 돌려준다.
            if (sagas.find(orderId) != null) return order
            throw ConflictException("order-not-payable", "현재 상태(${order.status})에서는 결제할 수 없습니다.")
        }
        val now = clock.instant()
        if (!now.isBefore(order.holdExpiresAt)) {
            throw ConflictException("order-hold-expired", "픽업 슬롯 점유 시간이 만료되었습니다. 다시 주문해 주세요.")
        }
        if (order.cardAmount.isPositive && cardToken.isBlank()) {
            throw BusinessException("card-token-required", HttpStatus.BAD_REQUEST, "카드 결제 금액이 있어 cardToken 이 필요합니다.")
        }

        order.startCheckout(now)
        val saga = CheckoutSaga.start(
            order.id,
            order.pointAmount,
            order.cardAmount,
            cardToken,
            now,
            now.plus(props.sagaTimeout),
            order.couponAmount,
        )
        when (saga.firstStep()) {
            CheckoutSaga.Next.RedeemPoints -> sendRedeem(order)
            CheckoutSaga.Next.ChargeCard -> sendCharge(order, saga)
            CheckoutSaga.Next.Complete -> error("unreachable")
        }
        orders.update(order)
        sagas.insert(saga, now)
        return order
    }

    @Transactional
    fun onPointsRedeemed(orderId: UUID) = withRunningSaga(orderId, "PointsRedeemed") { order, saga ->
        when (saga.onPointsRedeemed()) {
            CheckoutSaga.Next.ChargeCard -> sendCharge(order, saga)
            CheckoutSaga.Next.Complete -> complete(order, saga)
            CheckoutSaga.Next.RedeemPoints -> error("unreachable")
        }
    }

    @Transactional
    fun onPointsRedemptionFailed(orderId: UUID, reason: String = "INSUFFICIENT_BALANCE") = withRunningSaga(orderId, "PointsRedemptionFailed") { order, saga ->
        cancel(order, if (reason.startsWith("COUPON_")) CancelReason.COUPON_REJECTED else CancelReason.POINTS_INSUFFICIENT)
        saga.compensated()
    }

    @Transactional
    fun onPaymentCaptured(orderId: UUID) {
        val saga = sagas.find(orderId)
        if (saga != null && !saga.isRunning) {
            // 타임아웃으로 이미 보상한 Saga 에 늦게 도착한 승인. 타임아웃 시 RefundPayment 를 보냈으므로
            // payment-service 가 순서대로 취소한다. 여기서는 기록만 남긴다.
            log.warn("late PaymentCaptured ignored: orderId={}, sagaStatus={}", orderId, saga.status)
            return
        }
        withRunningSaga(orderId, "PaymentCaptured") { order, s ->
            if (s.onPaymentCaptured() == CheckoutSaga.Next.Complete) complete(order, s)
        }
    }

    @Transactional
    fun onPaymentFailed(orderId: UUID) = withRunningSaga(orderId, "PaymentFailed") { order, saga ->
        cancel(order, CancelReason.PAYMENT_DECLINED)
        if (saga.pointsRedeemed) {
            outbox.publish(
                Topics.LOYALTY_COMMANDS,
                order.id.toString(),
                ReverseRedemption(order.id.toString(), "PAYMENT_DECLINED"),
            )
        }
        saga.compensated()
    }

    /*
     * 주의: 각 블록은 "도메인 검증(예외 가능) → 부수효과(Outbox)" 순서를 지킨다.
     * 검증 예외를 무시하고 커밋할 때 부수효과가 반쯤 기록되는 일을 막기 위해서다.
     */

    /**
     * 응답이 오지 않는 Saga 를 정리한다. 어디까지 진행됐는지 모르므로 가능한 모든 보상을 보낸다(멱등).
     */
    @Transactional
    fun timeout(orderId: UUID) = withRunningSaga(orderId, "Timeout") { order, saga ->
        log.warn("checkout saga timed out: orderId={}, step={}", orderId, saga.step)
        cancel(order, CancelReason.PAYMENT_TIMEOUT)
        if (saga.cardAmount.isPositive && saga.step == SagaStep.CHARGING_PAYMENT) {
            outbox.publish(Topics.PAYMENT_COMMANDS, order.id.toString(), RefundPayment(order.id.toString(), "SAGA_TIMEOUT"))
        }
        if (saga.hasBenefits) {
            outbox.publish(Topics.LOYALTY_COMMANDS, order.id.toString(), ReverseRedemption(order.id.toString(), "SAGA_TIMEOUT"))
        }
        saga.timedOut()
    }

    private fun withRunningSaga(orderId: UUID, trigger: String, block: (Order, CheckoutSaga) -> Unit) {
        val order = orders.findByIdForUpdate(orderId) ?: run {
            log.warn("{} for unknown order {}", trigger, orderId)
            return
        }
        val saga = sagas.find(orderId)
        if (saga == null || !saga.isRunning) {
            log.info("{} ignored: saga not running (orderId={}, status={})", trigger, orderId, saga?.status)
            return
        }
        try {
            block(order, saga)
        } catch (e: SagaOutOfOrderException) {
            log.warn("{} ignored: out-of-order reply for saga at step {} ({})", trigger, saga.step, e.message)
            return
        } catch (e: IllegalOrderTransitionException) {
            log.warn("{} ignored: {}", trigger, e.message)
            return
        }
        orders.update(order)
        sagas.update(saga, clock.instant())
    }

    private fun complete(order: Order, saga: CheckoutSaga) {
        val now = clock.instant()
        order.markPaid(now)
        slots.confirm(order.id, now)
        saga.complete()
        events.paid(order)
    }

    private fun cancel(order: Order, reason: CancelReason) {
        val now = clock.instant()
        order.cancel(reason, now)
        slots.release(order.storeId, order.id, now)
        events.cancelled(order)
    }

    private fun sendRedeem(order: Order) = outbox.publish(
        Topics.LOYALTY_COMMANDS,
        order.id.toString(),
        RedeemPoints(
            order.id.toString(),
            order.memberId,
            order.brandId,
            order.storeId,
            order.pointAmount.won,
            order.couponId?.toString(),
            order.couponAmount.won,
            order.totalAmount.won,
        ),
    )

    private fun sendCharge(order: Order, saga: CheckoutSaga) = outbox.publish(
        Topics.PAYMENT_COMMANDS,
        order.id.toString(),
        ChargePayment(order.id.toString(), order.memberId, order.storeId, order.brandId, saga.cardAmount.won, saga.cardToken),
    )
}
