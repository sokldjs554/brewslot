dependencies {
    testImplementation(project(":services:order-service"))
    testImplementation(project(":services:payment-service"))
    testImplementation(project(":services:loyalty-service"))
    testImplementation(project(":services:settlement-service"))
    testImplementation(project(":services:notification-service"))
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(project(":libs:test-support"))
    testImplementation(project(":libs:common"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
