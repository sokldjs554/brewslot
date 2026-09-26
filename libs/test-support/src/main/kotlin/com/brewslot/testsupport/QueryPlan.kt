package com.brewslot.testsupport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * PostgreSQL 실행계획을 테스트에서 검증하기 위한 도구.
 *
 * "이 쿼리는 인덱스를 탄다" 는 사실을 코드 리뷰의 구두 합의가 아니라 회귀 테스트로 고정한다.
 * 스키마/쿼리 변경으로 계획이 Seq Scan 으로 퇴화하면 CI 가 실패한다.
 *
 * 분석 결과 JSON 은 `build/query-plans/<name>.json` 으로 저장되어 `tools/plan-doctor` 의 입력이 된다.
 */
class QueryPlan private constructor(val name: String, val root: JsonNode, val raw: String) {
    private val planRoot: JsonNode = root[0]["Plan"]

    val executionTimeMs: Double? get() = root[0]["Execution Time"]?.asDouble()
    val totalCost: Double get() = planRoot["Total Cost"].asDouble()

    fun nodes(): List<JsonNode> {
        val out = mutableListOf<JsonNode>()

        fun walk(n: JsonNode) {
            out.add(n)
            n["Plans"]?.forEach { walk(it) }
        }
        walk(planRoot)
        return out
    }

    fun nodeTypes(): List<String> = nodes().map { it["Node Type"].asText() }

    fun seqScannedRelations(): Set<String> =
        nodes().filter { it["Node Type"].asText() == "Seq Scan" }.map { it["Relation Name"].asText() }.toSet()

    fun usedIndexes(): Set<String> = nodes().mapNotNull { it["Index Name"]?.asText() }.toSet()

    fun hasSortNode(): Boolean = nodes().any { it["Node Type"].asText() == "Sort" }

    fun summary(): String = nodes().joinToString("\n") { n ->
        buildString {
            append(n["Node Type"].asText())
            n["Relation Name"]?.let { append(" on ").append(it.asText()) }
            n["Index Name"]?.let { append(" using ").append(it.asText()) }
            n["Actual Rows"]?.let { append(" rows=").append(it.asText()) }
            n["Actual Total Time"]?.let { append(" time=").append(it.asText()).append("ms") }
        }
    }

    override fun toString(): String = "QueryPlan($name)\n${summary()}"

    companion object {
        private val mapper = ObjectMapper()

        fun explain(
            dataSource: DataSource,
            name: String,
            sql: String,
            params: Map<String, Any?> = emptyMap(),
            analyze: Boolean = true,
        ): QueryPlan {
            val options = if (analyze) "ANALYZE, BUFFERS, FORMAT JSON" else "FORMAT JSON"
            val json = NamedParameterJdbcTemplate(dataSource)
                .queryForObject("EXPLAIN ($options) $sql", params, String::class.java)!!
            val plan = QueryPlan(name, mapper.readTree(json), json)
            persist(plan, sql)
            return plan
        }

        private fun persist(plan: QueryPlan, sql: String) {
            runCatching {
                val dir = Path.of("build", "query-plans")
                Files.createDirectories(dir)
                val doc = mapper.createObjectNode()
                doc.put("name", plan.name)
                doc.put("sql", sql.trimIndent())
                doc.set<JsonNode>("plan", plan.root)
                Files.writeString(dir.resolve("${plan.name}.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc))
            }
        }
    }
}
