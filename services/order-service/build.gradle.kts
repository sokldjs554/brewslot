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

// 테스트가 부수적으로 만드는 파일(스케줄러 교차검증 벡터, EXPLAIN JSON)을 출력으로 선언한다.
// 선언하지 않으면 빌드 캐시가 테스트를 FROM-CACHE 로 건너뛸 때 CI 의 verify·plan-doctor 단계가 입력을 잃는다.
tasks.test {
    outputs.file(layout.buildDirectory.file("scheduler-vectors.json"))
    outputs.dir(layout.buildDirectory.dir("query-plans"))
}
