package com.brewslot.order.ordering.domain

import java.time.Duration
import java.time.Instant

/**
 * 매장 지연 판단 (순수 함수).
 *
 * 매장이 밀렸는지는 "준비 완료됐어야 할 주문이 아직 안 나왔는가" 로 본다 — 각 주문의 계획된 마지막 제조 슬롯이
 * 끝났는데 READY 가 아니면 그만큼 밀린 것이다. 가장 오래 밀린 주문이 매장의 지연이고, 뒤 주문도 대략 그만큼 밀린다.
 */
object PickupDelay {
    /** 가장 오래 밀린 주문의 "준비됐어야 할 시각" 으로부터 지난 분(올림) */
    fun storeDelayMinutes(oldestOverdueSince: Instant, now: Instant): Int {
        val seconds = Duration.between(oldestOverdueSince, now).seconds
        return if (seconds <= 0) 0 else ((seconds + 59) / 60).toInt()
    }

    /**
     * 먼저 알릴 주문인가: 매장 지연이 기준 이상이고, 아직 받을 시각이 오지 않았고, 곧(horizon 안) 받을 주문.
     * 먼 미래 주문은 그 사이 매장이 따라잡을 수 있으므로 알리지 않는다.
     */
    fun shouldAlert(storeDelayMinutes: Int, thresholdMinutes: Int, pickupAt: Instant, now: Instant, horizon: Duration): Boolean =
        storeDelayMinutes >= thresholdMinutes && pickupAt.isAfter(now) && !pickupAt.isAfter(now.plus(horizon))

    /** 원래 매장에서 받을 때 예상 준비 시각 */
    fun expectedReadyAt(pickupAt: Instant, storeDelayMinutes: Int): Instant = pickupAt.plus(Duration.ofMinutes(storeDelayMinutes.toLong()))

    /** 다른 매장 제안 시각이 원래 매장에서 기다리는 것보다 나은가 (늦어지는 시각보다 이르면 이득) */
    fun isBetter(suggestedPickupAt: Instant, pickupAt: Instant, storeDelayMinutes: Int): Boolean =
        suggestedPickupAt.isBefore(expectedReadyAt(pickupAt, storeDelayMinutes))
}
