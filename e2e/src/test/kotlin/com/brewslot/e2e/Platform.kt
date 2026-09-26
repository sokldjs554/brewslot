package com.brewslot.e2e

import com.brewslot.loyalty.LoyaltyServiceApplication
import com.brewslot.order.OrderServiceApplication
import com.brewslot.payment.PaymentServiceApplication
import com.brewslot.settlement.SettlementServiceApplication
import com.brewslot.testsupport.Containers
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.client.RestClient

/**
 * 4개 서비스를 한 JVM 안에서 "각자의 ApplicationContext·DB·컨슈머 그룹" 으로 띄운다.
 * 서비스 간 통신은 실제 Kafka(Testcontainers)와 HTTP 로만 이뤄지므로, 배포 단위만 다를 뿐 운영과 같은 경로를 탄다.
 */
object Platform {
    lateinit var order: ConfigurableApplicationContext
    lateinit var payment: ConfigurableApplicationContext
    lateinit var loyalty: ConfigurableApplicationContext
    lateinit var settlement: ConfigurableApplicationContext

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        val common = mapOf(
            "server.port" to "0",
            "spring.kafka.bootstrap-servers" to Containers.kafka.bootstrapServers,
            "spring.datasource.username" to Containers.postgres.username,
            "spring.datasource.password" to Containers.postgres.password,
            "spring.data.redis.host" to Containers.redis.host,
            "spring.data.redis.port" to Containers.redis.getMappedPort(6379).toString(),
            "brewslot.outbox.poll-interval-ms" to "100",
            "management.tracing.sampling.probability" to "0.0",
            "spring.main.banner-mode" to "off",
        )
        payment = boot(
            PaymentServiceApplication::class.java,
            "payment-service",
            "payment_db",
            common,
            "brewslot.payment.recovery-poll-ms" to "500",
            "brewslot.payment.unresolved-grace" to "8s", // Saga 타임아웃(4s)이 먼저 나도록
        )
        loyalty = boot(LoyaltyServiceApplication::class.java, "loyalty-service", "loyalty_db", common)
        settlement = boot(
            SettlementServiceApplication::class.java,
            "settlement-service",
            "settlement_db",
            common,
            "brewslot.settlement.payment-service-url" to "http://localhost:${port(payment)}",
        )
        order = boot(
            OrderServiceApplication::class.java,
            "order-service",
            "order_db",
            common,
            "brewslot.order.saga-timeout" to "4s",
            "brewslot.order.expiry-poll-ms" to "500",
        )
        started = true
    }

    private fun boot(
        app: Class<*>,
        configName: String,
        database: String,
        common: Map<String, String>,
        vararg extra: Pair<String, String>,
    ): ConfigurableApplicationContext {
        val props = common + mapOf(
            "spring.config.name" to configName,
            "spring.datasource.url" to Containers.createDatabase("e2e_$database"),
        ) + extra
        // 기본 프로퍼티(defaultProperties)는 각 서비스 yml 보다 우선순위가 낮으므로 커맨드라인 인자로 넘긴다.
        return SpringApplicationBuilder(app).run(*props.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    fun port(ctx: ConfigurableApplicationContext) = ctx.environment.getProperty("local.server.port")!!.toInt()

    fun client(ctx: ConfigurableApplicationContext): RestClient = RestClient.builder().baseUrl("http://localhost:${port(ctx)}").build()
}
