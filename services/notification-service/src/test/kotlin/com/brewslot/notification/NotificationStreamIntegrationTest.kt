package com.brewslot.notification

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.codec.ServerSentEvent
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.Instant
import java.util.UUID

@AutoConfigureWebTestClient(timeout = "30s")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.config.name=notification-service"])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStreamIntegrationTest {
    @Autowired lateinit var web: WebTestClient

    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    @Autowired lateinit var registry: KafkaListenerEndpointRegistry

    private val type = object : ParameterizedTypeReference<ServerSentEvent<Notification>>() {}

    @BeforeAll
    fun waitForConsumer() {
        registry.listenerContainers.forEach { ContainerTestUtils.waitForAssignment(it, 3) }
    }

    private fun envelope(eventType: String, memberId: Long, orderId: String, extra: String = "") = """
        {"eventId":"${UUID.randomUUID()}","eventType":"$eventType","aggregateId":"$orderId","occurredAt":"${Instant.now()}",
         "payload":{"orderId":"$orderId","memberId":$memberId$extra}}
        """.trimIndent()

    private fun connect(memberId: Long, lastEventId: String? = null): Flux<ServerSentEvent<Notification>> = web.get().uri("/notifications/stream")
        .header("X-Member-Id", memberId.toString())
        .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
        .exchange().expectStatus().isOk
        .returnResult(type).responseBody
        .filter { it.data() != null }

    @Test
    fun `주문 이벤트가 오면 그 회원의 열린 연결로만 즉시 알림이 간다`() {
        val mine = connect(7001).take(2).collectList().toFuture()
        val other = connect(7002).take(1).collectList().toFuture()
        Thread.sleep(300) // 구독이 붙을 시간

        kafka.send("order.events", "o-1", envelope("OrderPaid", 7001, "o-1"))
        kafka.send("order.events", "o-1", envelope("OrderPlaced", 7001, "o-1")) // 알림 대상 아님
        val times = ""","promisedPickupAt":"2026-09-28T00:45:00Z","readyAt":"2026-09-28T00:42:00Z""""
        kafka.send("order.events", "o-1", envelope("OrderReady", 7001, "o-1", times))

        val received = mine.get(20, java.util.concurrent.TimeUnit.SECONDS)
        assertThat(received.map { it.event() }).containsExactly("OrderPaid", "OrderReady")
        assertThat(received.last().data()!!.body).contains("3분 먼저")
        assertThat(received.last().id()).isEqualTo(received.last().data()!!.id)
        // 결제 확정은 울리지 않고 준비 완료만 울린다. 둘 다 같은 주문 카드로 합쳐진다.
        assertThat(received.map { it.data()!!.delivery }).containsExactly(Delivery.QUIET, Delivery.ALERT)
        assertThat(received.map { it.data()!!.collapseKey }).containsOnly("order:o-1")

        Thread.sleep(500)
        assertThat(other.isDone).isFalse() // 다른 회원에게는 아무것도 가지 않았다
        other.cancel(true)
    }

    @Test
    fun `앱이 잠깐 끊겼다 다시 붙으면 Last-Event-ID 이후 알림을 이어서 받는다`() {
        val first = connect(7003).take(1).collectList().toFuture()
        Thread.sleep(300)
        kafka.send("order.events", "o-2", envelope("OrderPaid", 7003, "o-2"))
        val cursor = first.get(20, java.util.concurrent.TimeUnit.SECONDS).single().id()!!

        // 연결이 끊긴 사이에 온 알림
        kafka.send("order.events", "o-2", envelope("OrderPreparing", 7003, "o-2"))
        Thread.sleep(Duration.ofSeconds(2).toMillis())

        val resumed = connect(7003, cursor).take(1).blockFirst(Duration.ofSeconds(20))!!
        assertThat(resumed.event()).isEqualTo("OrderPreparing")
    }

    companion object {
        private val kafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1")).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers)).use {
                runCatching { it.createTopics(listOf(NewTopic("order.events", 3, 1))).all().get() }
            }
            registry.add("spring.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
        }
    }
}
