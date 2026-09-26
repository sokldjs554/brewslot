package com.brewslot.settlement

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class SettlementServiceApplication

fun main(args: Array<String>) {
    runApplication<SettlementServiceApplication>(*args) {
        setDefaultProperties(mapOf("spring.config.name" to "settlement-service"))
    }
}
