plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.testcontainers:junit-jupiter")
    api("org.testcontainers:postgresql")
    api("org.testcontainers:kafka")
    api("org.awaitility:awaitility-kotlin")
    api("org.springframework.kafka:spring-kafka")
    api("com.tngtech.archunit:archunit-junit5:1.4.1")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
}
