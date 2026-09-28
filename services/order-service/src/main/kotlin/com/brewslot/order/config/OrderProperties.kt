package com.brewslot.order.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "brewslot.order")
data class OrderProperties(
    /** 슬롯 임시 점유 유지 시간. 이 안에 결제를 시작하지 않으면 점유가 풀린다. */
    val holdTtl: Duration = Duration.ofMinutes(5),
    /** 결제 Saga 최대 진행 시간. 넘기면 보상 후 취소한다. */
    val sagaTimeout: Duration = Duration.ofMinutes(2),
    /** 동시 예약 경합으로 계획이 무효화됐을 때 재계획 횟수 */
    val maxReservationAttempts: Int = 3,
    /** 매장 변경 요청에 새 매장이 답해야 하는 기한. 넘기면 새 자리를 풀고 원래 주문을 유지한다. */
    val transferAcceptTimeout: Duration = Duration.ofMinutes(2),
    /** 다른 매장을 제안할 때 원래 매장에서 걸어갈 수 있는 최대 시간(분) */
    val maxWalkMinutes: Int = 15,
    /** 매장이 이만큼 밀리면(준비됐어야 할 주문이 아직 안 나옴) 곧 받을 고객에게 먼저 알린다 */
    val delayAlertMinutes: Int = 5,
    /** 지연 안내 대상: 지금부터 이 시간 안에 받을 주문 */
    val delayAlertHorizon: Duration = Duration.ofMinutes(45),
)
