package com.brewslot.order

import com.brewslot.testsupport.Containers
import com.brewslot.testsupport.MutableClock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.config.name=order-service",
        // 시간 기반 잡은 테스트가 직접 호출한다
        "brewslot.order.expiry-poll-ms=3600000",
        "brewslot.order.transfer-poll-ms=3600000",
        "brewslot.order.risk-poll-ms=3600000",
    ],
)
@Import(OrderIntegrationTest.TestClockConfig::class)
abstract class OrderIntegrationTest {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var clock: MutableClock

    fun headers(memberId: Long, idempotencyKey: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Member-Id", memberId.toString())
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    fun <T> entity(body: T, memberId: Long, idempotencyKey: String? = null) = HttpEntity(body, headers(memberId, idempotencyKey))

    @TestConfiguration
    class TestClockConfig {
        @Bean
        @Primary
        fun mutableClock() = MutableClock(START)
    }

    companion object {
        /** 2026-09-28 08:00 KST */
        val START: Instant = Instant.parse("2026-09-27T23:00:00Z")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            Containers.registerPostgres(registry, "order_db")
            Containers.registerKafka(registry)
            Containers.registerRedis(registry)
        }
    }
}
