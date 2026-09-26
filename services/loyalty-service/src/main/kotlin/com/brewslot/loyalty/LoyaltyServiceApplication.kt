package com.brewslot.loyalty

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class LoyaltyServiceApplication

fun main(args: Array<String>) {
    runApplication<LoyaltyServiceApplication>(*args) {
        setDefaultProperties(mapOf("spring.config.name" to "loyalty-service"))
    }
}
