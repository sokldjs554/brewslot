package com.brewslot.notification

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@ConfigurationProperties(prefix = "brewslot.notification")
data class NotificationProperties(
    val replaySize: Int = 20,
    val heartbeat: Duration = Duration.ofSeconds(15),
    val idleEviction: Duration = Duration.ofMinutes(10),
)

/**
 * 회원별 알림 채널. 스레드를 붙잡지 않는 Reactor Sink 라, 연결이 수만 개여도 연결당 스레드가 필요 없다.
 *
 * 회원마다 최근 알림 [NotificationProperties.replaySize] 건을 기억해, 앱이 잠깐 끊겼다 다시 붙으면(Last-Event-ID)
 * 그 사이 알림을 이어서 보낸다. 새로 연결하면(Last-Event-ID 없음) 지금부터의 알림만 받는다.
 */
@Component
class NotificationHub(private val props: NotificationProperties, registry: MeterRegistry) {
    private class Channel(size: Int) {
        val sink: Sinks.Many<Notification> = Sinks.many().replay().limit(size)

        @Volatile var lastActivity: Instant = Instant.now()
    }

    private val channels = ConcurrentHashMap<Long, Channel>()
    private val connections = AtomicInteger()
    private val pushed = registry.counter("brewslot.notification.pushed")

    init {
        Gauge.builder("brewslot.notification.connections") { connections.get().toDouble() }
            .description("열려 있는 SSE 연결 수").register(registry)
    }

    private fun channel(memberId: Long) = channels.computeIfAbsent(memberId) { Channel(props.replaySize) }

    fun publish(n: Notification) {
        val ch = channel(n.memberId)
        ch.lastActivity = Instant.now()
        // 여러 Kafka 소비 스레드가 같은 회원에게 동시에 보낼 수 있으므로 실패 시 재시도하는 emit 을 쓴다.
        ch.sink.emitNext(n, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(50)))
        pushed.increment()
    }

    fun stream(memberId: Long, lastEventId: String?): Flux<Notification> {
        val connectedAt = Instant.now()
        val ch = channel(memberId)
        return ch.sink.asFlux()
            .filter { n -> if (lastEventId == null) !n.occurredAt.isBefore(connectedAt.minusSeconds(1)) else n.isAfter(lastEventId) }
            .doOnSubscribe {
                connections.incrementAndGet()
                ch.lastActivity = Instant.now()
            }
            .doFinally {
                connections.decrementAndGet()
                ch.lastActivity = Instant.now()
            }
    }

    val heartbeat: Duration get() = props.heartbeat

    /** 아무도 구독하지 않고 오래 조용한 회원 채널을 정리해 메모리를 돌려준다. */
    @Scheduled(fixedDelay = 60_000)
    fun evictIdle() {
        val cutoff = Instant.now().minus(props.idleEviction)
        channels.entries.removeIf { (_, ch) -> ch.sink.currentSubscriberCount() == 0 && ch.lastActivity.isBefore(cutoff) }
    }

    fun channelCount() = channels.size
}
