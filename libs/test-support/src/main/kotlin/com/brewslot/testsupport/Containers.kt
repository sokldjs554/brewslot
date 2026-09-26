package com.brewslot.testsupport

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트 JVM 하나당 한 번만 띄우는 공유 컨테이너 (Singleton Container 패턴).
 * H2 같은 대체 DB 를 쓰지 않는 이유: 이 프로젝트의 핵심 로직(조건부 UPDATE, SKIP LOCKED,
 * advisory lock, 부분 인덱스, 실행계획)이 전부 PostgreSQL 고유 동작에 기대고 있기 때문이다.
 */
object Containers {
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("brewslot")
            .withUsername("brewslot")
            .withPassword("brewslot")
            .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off")
            .apply { start() }
    }

    val kafka: KafkaContainer by lazy {
        KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            .withEnv("KAFKA_NUM_PARTITIONS", "3")
            .apply { start() }
    }

    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .apply { start() }
    }

    /** 같은 PostgreSQL 컨테이너 안에 서비스별 데이터베이스를 만든다 (database-per-service). */
    fun createDatabase(name: String): String {
        postgres.createConnection("").use { conn ->
            conn.createStatement().use { st ->
                val exists = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$name'").use { it.next() }
                if (!exists) st.execute("CREATE DATABASE $name")
            }
        }
        return postgres.jdbcUrl.replace("/brewslot", "/$name")
    }

    fun registerPostgres(registry: DynamicPropertyRegistry, database: String) {
        val url = createDatabase(database)
        registry.add("spring.datasource.url") { url }
        registry.add("spring.datasource.username") { postgres.username }
        registry.add("spring.datasource.password") { postgres.password }
    }

    fun registerKafka(registry: DynamicPropertyRegistry) {
        registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
    }

    fun registerRedis(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host") { redis.host }
        registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
    }
}
