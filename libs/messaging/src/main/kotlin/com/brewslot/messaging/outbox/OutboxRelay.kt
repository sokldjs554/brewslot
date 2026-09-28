package com.brewslot.messaging.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 커밋된 outbox 행을 Kafka 로 전달한다 (at-least-once).
 *
 * - 트랜잭션 단위 advisory lock 으로 "한 번에 하나의 릴레이"만 동작한다.
 *   여러 인스턴스가 SKIP LOCKED 로 배치를 나눠 가지면 같은 주문의 이벤트 순서가 뒤바뀔 수 있기 때문이다.
 *   (처리량보다 파티션 내 순서가 중요하다는 판단. docs/adr/0003-outbox-relay.md)
 * - Kafka 전송이 모두 ack 된 뒤에만 published_at 을 기록한다. 중간에 죽으면 다음 폴링에서 재전송되고,
 *   소비자 쪽 Inbox 가 중복을 흡수한다.
 */
class OutboxRelay(
    private val jdbc: JdbcClient,
    private val kafka: KafkaTemplate<String, String>,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val batchSize: Int,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val published: Counter = Counter.builder("brewslot.outbox.published").register(meterRegistry)
    private val lag: Timer = Timer.builder("brewslot.outbox.lag")
        .description("outbox 적재부터 Kafka ack 까지의 지연")
        .publishPercentiles(0.5, 0.99)
        .register(meterRegistry)

    init {
        // 미발행 적체량. Kafka 장애·릴레이 정지를 "지연" 보다 먼저 드러낸다. 부분 인덱스(published_at IS NULL)라 조회가 싸다.
        Gauge.builder("brewslot.outbox.pending") {
            jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL").query(Long::class.java).single().toDouble()
        }.description("아직 Kafka 로 발행되지 않은 outbox 행 수").register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${brewslot.outbox.poll-interval-ms:200}")
    fun relayScheduled() {
        try {
            relayAll()
        } catch (e: Exception) {
            log.warn("outbox relay failed; will retry on next poll: {}", e.toString())
        }
    }

    /** 미발행 행이 없을 때까지 반복한다. 발행한 건수를 반환한다. */
    fun relayAll(): Int {
        var total = 0
        while (true) {
            val n = relayBatch()
            total += n
            if (n < batchSize) return total
        }
    }

    fun relayBatch(): Int = tx.execute {
        val acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
            .param("key", ADVISORY_LOCK_KEY)
            .query(Boolean::class.java)
            .single()
        if (!acquired) return@execute 0

        val rows = jdbc.sql(
            """
            SELECT id, event_id, topic, message_key, event_type, envelope::text AS envelope, trace_parent, created_at
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        )
            .param("limit", batchSize)
            .query { rs, _ ->
                Row(
                    id = rs.getLong("id"),
                    eventId = rs.getObject("event_id", UUID::class.java),
                    topic = rs.getString("topic"),
                    key = rs.getString("message_key"),
                    eventType = rs.getString("event_type"),
                    envelope = rs.getString("envelope"),
                    traceParent = rs.getString("trace_parent"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
            .list()
        if (rows.isEmpty()) return@execute 0

        val futures = rows.map { row ->
            val record = ProducerRecord<String, String>(row.topic, row.key, row.envelope)
            record.headers().add(RecordHeader(HEADER_EVENT_ID, row.eventId.toString().toByteArray()))
            record.headers().add(RecordHeader(HEADER_EVENT_TYPE, row.eventType.toByteArray()))
            row.traceParent?.let { record.headers().add(RecordHeader("traceparent", it.toByteArray())) }
            kafka.send(record)
        }
        futures.forEach { it.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        val now = clock.instant()
        jdbc.sql("UPDATE outbox_event SET published_at = :now WHERE id IN (:ids)")
            .param("now", java.sql.Timestamp.from(now))
            .param("ids", rows.map { it.id })
            .update()
        published.increment(rows.size.toDouble())
        rows.forEach { lag.record(Duration.between(it.createdAt, now).abs()) }
        rows.size
    } ?: 0

    @Scheduled(cron = "\${brewslot.outbox.cleanup-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun cleanupPublished() {
        val threshold = clock.instant().minus(RETENTION)
        val deleted = jdbc.sql(
            """
            DELETE FROM outbox_event
            WHERE id IN (SELECT id FROM outbox_event WHERE published_at < :threshold LIMIT 10000)
            """.trimIndent(),
        ).param("threshold", java.sql.Timestamp.from(threshold)).update()
        if (deleted > 0) log.info("outbox cleanup: deleted {} published rows", deleted)
    }

    private data class Row(
        val id: Long,
        val eventId: UUID,
        val topic: String,
        val key: String,
        val eventType: String,
        val envelope: String,
        val traceParent: String?,
        val createdAt: Instant,
    )

    companion object {
        const val HEADER_EVENT_ID = "x-event-id"
        const val HEADER_EVENT_TYPE = "x-event-type"
        private const val ADVISORY_LOCK_KEY = 0x0B5E_7001L
        private const val SEND_TIMEOUT_SECONDS = 10L
        private val RETENTION: Duration = Duration.ofDays(7)
    }
}
