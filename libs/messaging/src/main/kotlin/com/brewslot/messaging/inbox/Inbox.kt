package com.brewslot.messaging.inbox

import com.brewslot.messaging.EventEnvelope
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * Idempotent Consumer.
 *
 * "처리 완료 표시"와 "비즈니스 부수효과"를 같은 트랜잭션에 묶는다.
 * - 핸들러가 성공하면 inbox 행과 부수효과가 함께 커밋된다.
 * - 핸들러가 실패하면 둘 다 롤백되고, Kafka 에러 핸들러가 재시도 후 DLT 로 보낸다.
 * - 같은 eventId 가 다시 오면 INSERT 가 무시되어 핸들러를 호출하지 않는다.
 */
class Inbox(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(consumer: String, envelope: EventEnvelope, handler: () -> Unit): Boolean =
        tx.execute {
            val inserted = jdbc.sql(
                """
                INSERT INTO inbox_message (consumer, event_id, event_type)
                VALUES (:consumer, :eventId, :eventType)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            )
                .param("consumer", consumer)
                .param("eventId", envelope.eventId)
                .param("eventType", envelope.eventType)
                .update()
            if (inserted == 0) {
                log.debug("duplicate message skipped: consumer={}, eventId={}", consumer, envelope.eventId)
                return@execute false
            }
            handler()
            true
        } ?: false
}
