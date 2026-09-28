package com.brewslot.notification

import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * 고객 앱의 실시간 주문 알림 (Server-Sent Events).
 * 인증은 게이트웨이가 끝내고 X-Member-Id 를 넣어 준다고 가정한다(다른 서비스와 같은 규칙).
 */
@RestController
class NotificationController(private val hub: NotificationHub) {
    @GetMapping("/notifications/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestHeader("X-Member-Id") memberId: Long,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): Flux<ServerSentEvent<Notification>> {
        val events = hub.stream(memberId, lastEventId)
            .map { ServerSentEvent.builder(it).id(it.id).event(it.type).build() }
        // 프록시·로드밸런서가 조용한 연결을 끊지 않도록 주기적으로 주석 이벤트를 보낸다.
        val heartbeat = Flux.interval(hub.heartbeat).map { ServerSentEvent.builder<Notification>().comment("keep-alive").build() }
        // 연결 직후 한 번 보내 응답 헤더를 바로 내려보낸다(클라이언트가 "연결됨" 을 즉시 알 수 있음).
        val opened = Flux.just(ServerSentEvent.builder<Notification>().comment("connected").build())
        return Flux.concat(opened, Flux.merge(events, heartbeat))
    }
}
