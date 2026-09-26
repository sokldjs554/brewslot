plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:web"))
    implementation(project(":libs:messaging"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.micrometer:micrometer-registry-datadog")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:test-support"))
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
}
