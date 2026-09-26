package com.brewslot.order.ordering.saga

import com.brewslot.common.Money
import java.time.Instant
import java.util.UUID

/**
 * 결제 Saga 의 진행 상태 (Orchestration 방식).
 *
 * 순서: [포인트 차감] → [카드 결제] → 주문 확정(슬롯 CONFIRMED)
 * 포인트를 먼저 차감하는 이유: 포인트 부족은 "흔하고 값싼 실패" 이고 카드 승인 취소는 "드물고 비싼 보상" 이다.
 * 실패 확률이 높고 보상 비용이 낮은 단계를 앞에 두면 PG 취소(망취소) 발생 빈도가 줄어든다.
 */
enum class SagaStep { REDEEMING_POINTS, CHARGING_PAYMENT, DONE }

enum class SagaStatus { RUNNING, COMPLETED, COMPENSATED, TIMED_OUT }

/** 현재 단계와 맞지 않는 응답(중복·역순). 오케스트레이터는 이를 무시한다. */
class SagaOutOfOrderException(message: String) : RuntimeException(message)

class CheckoutSaga(
    val orderId: UUID,
    val pointAmount: Money,
    val cardAmount: Money,
    val cardToken: String,
    step: SagaStep,
    status: SagaStatus,
    pointsRedeemed: Boolean,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val version: Long,
) {
    var step: SagaStep = step
        private set
    var status: SagaStatus = status
        private set
    var pointsRedeemed: Boolean = pointsRedeemed
        private set

    val isRunning: Boolean get() = status == SagaStatus.RUNNING

    /** 다음에 해야 할 일. 오케스트레이터는 이 결과대로 명령을 발행한다. */
    sealed interface Next {
        data object RedeemPoints : Next

        data object ChargeCard : Next

        data object Complete : Next
    }

    fun firstStep(): Next = when {
        pointAmount.isPositive -> Next.RedeemPoints.also { step = SagaStep.REDEEMING_POINTS }
        else -> Next.ChargeCard.also { step = SagaStep.CHARGING_PAYMENT }
    }

    fun onPointsRedeemed(): Next {
        if (step != SagaStep.REDEEMING_POINTS) throw SagaOutOfOrderException("unexpected PointsRedeemed at step $step")
        pointsRedeemed = true
        return if (cardAmount.isPositive) {
            step = SagaStep.CHARGING_PAYMENT
            Next.ChargeCard
        } else {
            Next.Complete
        }
    }

    fun onPaymentCaptured(): Next {
        if (step != SagaStep.CHARGING_PAYMENT) throw SagaOutOfOrderException("unexpected PaymentCaptured at step $step")
        return Next.Complete
    }

    fun complete() {
        step = SagaStep.DONE
        status = SagaStatus.COMPLETED
    }

    fun compensated() {
        status = SagaStatus.COMPENSATED
    }

    fun timedOut() {
        status = SagaStatus.TIMED_OUT
    }

    companion object {
        fun start(orderId: UUID, pointAmount: Money, cardAmount: Money, cardToken: String, now: Instant, deadlineAt: Instant) =
            CheckoutSaga(
                orderId,
                pointAmount,
                cardAmount,
                cardToken,
                SagaStep.REDEEMING_POINTS,
                SagaStatus.RUNNING,
                false,
                now,
                deadlineAt,
                0,
            )
    }
}
