package com.brewslot.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * order.events → 회원 알림. 봉투 형식은 libs:messaging 의 EventEnvelope 와 같다(모르는 필드는 무시).
 * 알림은 "최선 노력" 전달이다: 놓쳐도 주문·결제 결과에는 영향이 없고, 앱은 주문 조회 API 로 언제든 최신 상태를 본다.
 */
@Component
class OrderEventListener(private val mapper: ObjectMapper, private val hub: NotificationHub) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [ORDER_EVENTS])
    fun on(message: String) {
        val env = try {
            mapper.readTree(message)
        } catch (e: Exception) {
            log.warn("unreadable order event skipped: {}", e.toString())
            return
        }
        val n = NotificationMapper.from(
            env.path("eventId").asText(),
            env.path("eventType").asText(),
            Instant.parse(env.path("occurredAt").asText()),
            env.path("payload"),
        ) ?: return
        hub.publish(n)
    }

    companion object {
        /** libs:messaging Topics.ORDER_EVENTS 와 같은 값 (이 서비스는 JDBC Outbox 라이브러리를 들이지 않는다) */
        const val ORDER_EVENTS = "order.events"
    }
}
