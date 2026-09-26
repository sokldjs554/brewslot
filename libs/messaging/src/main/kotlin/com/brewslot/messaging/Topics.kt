package com.brewslot.messaging

/**
 * 토픽 명명 규칙: `<소유 서비스 컨텍스트>.<events|commands>`
 *
 * - events  : 소유 서비스가 "이미 일어난 사실"을 발행한다. 구독자는 누구든 될 수 있다.
 * - commands: 소유 서비스가 "해 달라"는 요청을 받는다. 발행자는 Saga 오케스트레이터(order-service) 뿐이다.
 *
 * 메시지 key 는 항상 orderId(또는 aggregate id)로, 같은 주문에 대한 명령/이벤트의 파티션 내 순서를 보장한다.
 */
object Topics {
    const val ORDER_EVENTS = "order.events"
    const val PAYMENT_EVENTS = "payment.events"
    const val LOYALTY_EVENTS = "loyalty.events"
    const val PAYMENT_COMMANDS = "payment.commands"
    const val LOYALTY_COMMANDS = "loyalty.commands"

    const val DLT_SUFFIX = ".DLT"
}
