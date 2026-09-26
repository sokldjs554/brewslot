plugins {
    `java-library`
}

dependencies {
    api(project(":libs:common"))
    api("org.springframework.kafka:spring-kafka")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api("io.micrometer:micrometer-tracing")
}
