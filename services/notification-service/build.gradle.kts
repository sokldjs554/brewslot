plugins {
    id("org.springframework.boot")
}

// 알림 서비스는 DB 가 없다. 수많은 고객 앱이 연결을 오래 붙잡고 있는(SSE) I/O 중심 서비스라 WebFlux(Netty) 로 만든다.
// libs:messaging 은 JDBC Outbox 를 끌고 오므로 의존하지 않고, 봉투(JSON)만 직접 읽는다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.micrometer:micrometer-registry-datadog")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.awaitility:awaitility-kotlin")
}
