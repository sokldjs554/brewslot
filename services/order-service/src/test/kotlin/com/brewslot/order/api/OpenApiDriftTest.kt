package com.brewslot.order.api

import com.brewslot.order.OrderIntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * API-first 계약 검증: 사람이 설계한 명세(docs/api/order-service.yaml)와
 * 실제 구현에서 생성된 명세(springdoc /v3/api-docs)가 어긋나면 실패한다.
 * "문서는 있는데 구현이 다르다" 또는 "문서에 없는 API 가 생겼다" 를 리뷰가 아니라 빌드가 잡는다.
 */
class OpenApiDriftTest : OrderIntegrationTest() {
    private val designed: JsonNode by lazy {
        ObjectMapper(YAMLFactory()).readTree(Path.of(System.getProperty("brewslot.rootDir"), "docs/api/order-service.yaml").toFile())
    }

    private val implemented: JsonNode by lazy { ObjectMapper().readTree(rest.getForObject("/v3/api-docs", String::class.java)) }

    private val methods = setOf("get", "put", "post", "delete", "patch")

    private fun operations(api: JsonNode): Map<String, JsonNode> = api["paths"].properties().flatMap { (path, item) ->
        item.properties().filter { it.key in methods }.map { (method, op) -> "${method.uppercase()} $path" to op }
    }.toMap()

    private fun requiredHeaders(api: JsonNode, op: JsonNode): Set<String> = (op["parameters"]?.toList() ?: emptyList())
        .map { p -> p["\$ref"]?.let { ref -> api.at(ref.asText().removePrefix("#")) } ?: p }
        .filter { it["in"]?.asText() == "header" && it["required"]?.asBoolean() == true }
        .map { it["name"].asText() }
        .toSet()

    @Test
    fun `설계 명세와 구현의 엔드포인트(경로+메서드)가 정확히 일치한다`() {
        assertThat(operations(implemented).keys).containsExactlyInAnyOrderElementsOf(operations(designed).keys)
    }

    @Test
    fun `설계 명세에서 필수인 헤더는 구현에서도 필수다`() {
        val impl = operations(implemented)
        operations(designed).forEach { (key, op) ->
            assertThat(requiredHeaders(implemented, impl.getValue(key)))
                .describedAs(key)
                .containsAll(requiredHeaders(designed, op))
        }
    }
}
