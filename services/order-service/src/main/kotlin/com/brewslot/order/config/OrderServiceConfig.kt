package com.brewslot.order.config

import com.brewslot.messaging.Topics
import com.github.benmanes.caffeine.cache.Caffeine
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import java.time.Duration

@Configuration
class OrderServiceConfig {
    /** 이 서비스가 "소유"하는 토픽만 선언한다. */
    @Bean
    fun orderEventsTopic(): NewTopic = TopicBuilder.name(Topics.ORDER_EVENTS).partitions(PARTITIONS).build()

    @Bean
    fun paymentCommandsTopic(): NewTopic = TopicBuilder.name(Topics.PAYMENT_COMMANDS).partitions(PARTITIONS).build()

    @Bean
    fun loyaltyCommandsTopic(): NewTopic = TopicBuilder.name(Topics.LOYALTY_COMMANDS).partitions(PARTITIONS).build()

    @Bean
    fun cacheManager(): CacheManager = CaffeineCacheManager().apply {
        setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(10_000))
    }

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info().title("BrewSlot Order Service").version("v1")
            .description("픽업 약속 기반 주문 · 제조 슬롯 스케줄링 · 결제 Saga 오케스트레이션"),
    )

    companion object {
        const val PARTITIONS = 3
    }
}
