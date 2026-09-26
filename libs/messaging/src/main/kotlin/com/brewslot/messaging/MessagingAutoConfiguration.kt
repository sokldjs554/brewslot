package com.brewslot.messaging

import com.brewslot.messaging.inbox.Inbox
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.messaging.outbox.OutboxRelay
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.backoff.ExponentialBackOff
import java.time.Clock

@AutoConfiguration(
    after = [
        JacksonAutoConfiguration::class,
        KafkaAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
    ],
)
class MessagingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun envelopeCodec(objectMapper: ObjectMapper) = EnvelopeCodec(objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun outbox(
        jdbc: JdbcClient,
        codec: EnvelopeCodec,
        clock: Clock,
        tracer: ObjectProvider<Tracer>,
        propagator: ObjectProvider<Propagator>,
    ) = Outbox(jdbc, codec, clock, tracer, propagator)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "brewslot.outbox", name = ["relay-enabled"], havingValue = "true", matchIfMissing = true)
    fun outboxRelay(
        jdbc: JdbcClient,
        kafka: KafkaTemplate<String, String>,
        transactionManager: PlatformTransactionManager,
        clock: Clock,
        meterRegistry: MeterRegistry,
        @Value("\${brewslot.outbox.batch-size:200}") batchSize: Int,
    ) = OutboxRelay(jdbc, kafka, TransactionTemplate(transactionManager), clock, batchSize, meterRegistry)

    @Bean
    @ConditionalOnMissingBean
    fun inbox(jdbc: JdbcClient, transactionManager: PlatformTransactionManager) =
        Inbox(jdbc, TransactionTemplate(transactionManager))

    /**
     * 소비 실패 시 지수 백오프로 재시도한 뒤 `<topic>.DLT` 로 격리한다.
     * DLT 는 파티션 수가 원본과 다를 수 있으므로 파티션은 브로커가 고르게 한다(-1).
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler::class)
    fun kafkaErrorHandler(kafka: KafkaTemplate<String, String>): CommonErrorHandler {
        val log = LoggerFactory.getLogger("com.brewslot.messaging.DeadLetter")
        val recoverer = DeadLetterPublishingRecoverer(kafka) { record, ex ->
            log.error("message moved to DLT: topic={}, key={}, cause={}", record.topic(), record.key(), ex.toString())
            TopicPartition(record.topic() + Topics.DLT_SUFFIX, -1)
        }
        val backOff = ExponentialBackOff(200, 2.0).apply { maxElapsedTime = 3_000 }
        return DefaultErrorHandler(recoverer, backOff)
    }
}
