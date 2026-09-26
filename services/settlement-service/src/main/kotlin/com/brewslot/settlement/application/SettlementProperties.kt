package com.brewslot.settlement.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "brewslot.settlement")
data class SettlementProperties(
    val pgFeeBps: Int = 220,
    val platformFeeBps: Int = 100,
    val paymentServiceUrl: String = "http://localhost:8082",
)
