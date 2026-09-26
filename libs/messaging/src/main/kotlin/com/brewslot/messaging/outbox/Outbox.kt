package com.brewslot.messaging.outbox

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

/**
 * 메시지 발행의 유일한 진입점.
 *
 * KafkaTemplate 을 직접 호출하면 "DB 커밋은 됐는데 메시지는 유실" 또는 "메시지는 나갔는데 DB 는 롤백" 이
 * 발생한다(dual write). 대신 비즈니스 트랜잭션 안에서 outbox 테이블에 INSERT 만 하고,
 * [OutboxRelay] 가 커밋된 행을 Kafka 로 옮긴다.
 */
class Outbox(
    private val jdbc: JdbcClient,
    private val codec: EnvelopeCodec,
    private val clock: Clock,
    private val tracer: ObjectProvider<Tracer>,
    private val propagator: ObjectProvider<Propagator>,
) {
    fun publish(
        topic: String,
        aggregateId: String,
        payload: Any,
        correlationId: String? = aggregateId,
        causationId: UUID? = null,
    ): UUID {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Outbox.publish 는 비즈니스 트랜잭션 안에서만 호출할 수 있습니다 (topic=$topic)"
        }
        val envelope = EventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = payload::class.java.simpleName,
            aggregateId = aggregateId,
            occurredAt = clock.instant(),
            correlationId = correlationId,
            causationId = causationId,
            payload = codec.mapper.valueToTree(payload),
        )
        jdbc.sql(
            """
            INSERT INTO outbox_event (event_id, topic, message_key, event_type, envelope, trace_parent, created_at)
            VALUES (:eventId, :topic, :key, :eventType, CAST(:envelope AS jsonb), :traceParent, :createdAt)
            """.trimIndent(),
        )
            .param("eventId", envelope.eventId)
            .param("topic", topic)
            .param("key", aggregateId)
            .param("eventType", envelope.eventType)
            .param("envelope", codec.encode(envelope))
            .param("traceParent", currentTraceParent())
            .param("createdAt", java.sql.Timestamp.from(envelope.occurredAt))
            .update()
        return envelope.eventId
    }

    private fun currentTraceParent(): String? {
        val t = tracer.ifAvailable ?: return null
        val p = propagator.ifAvailable ?: return null
        val context = t.currentTraceContext().context() ?: return null
        val carrier = mutableMapOf<String, String>()
        p.inject(context, carrier) { c, k, v -> c!![k] = v }
        return carrier["traceparent"]
    }
}
