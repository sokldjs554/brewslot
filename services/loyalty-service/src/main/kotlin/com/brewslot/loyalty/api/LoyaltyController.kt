package com.brewslot.loyalty.api

import com.brewslot.loyalty.application.PointService
import com.brewslot.messaging.Topics
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.config.TopicBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class WalletResponse(val brandId: Long, val balance: Long, val expiringWithin30Days: Long)

data class PointHistoryItem(val transactionId: Long, val type: String, val amount: Long, val orderId: String?, val memo: String?, val createdAt: Instant)

data class PointHistoryPage(val items: List<PointHistoryItem>, val nextCursor: Long?)

data class GrantRequest(
    val memberId: Long,
    val brandId: Long,
    @field:Positive val amount: Long,
    val expiryDays: Int = 365,
    val memo: String = "프로모션 지급",
)

@Tag(name = "Points")
@RestController
class LoyaltyController(private val jdbc: JdbcClient, private val points: PointService, private val clock: Clock) {
    @Operation(summary = "회원의 브랜드별 포인트 잔액과 30일 내 소멸 예정 포인트")
    @GetMapping("/members/{memberId}/wallets")
    fun wallets(
        @PathVariable memberId: Long,
    ): List<WalletResponse> {
        val now = clock.instant()
        return jdbc.sql(
            """
            SELECT w.brand_id, w.balance,
                   COALESCE((SELECT SUM(remaining) FROM point_lot l
                             WHERE l.member_id = w.member_id AND l.brand_id = w.brand_id AND l.remaining > 0
                               AND l.expires_at > :now AND l.expires_at <= :soon), 0) AS expiring
            FROM point_wallet w WHERE w.member_id = :m ORDER BY w.brand_id
            """.trimIndent(),
        )
            .param("m", memberId)
            .param("now", Timestamp.from(now))
            .param("soon", Timestamp.from(now.plus(Duration.ofDays(30))))
            .query { rs, _ -> WalletResponse(rs.getLong("brand_id"), rs.getLong("balance"), rs.getLong("expiring")) }
            .list()
    }

    @Operation(summary = "포인트 거래 내역 (커서 = 마지막으로 받은 transactionId)")
    @GetMapping("/members/{memberId}/wallets/{brandId}/transactions")
    fun history(
        @PathVariable memberId: Long,
        @PathVariable brandId: Long,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "20") size: Int,
    ): PointHistoryPage {
        val limit = size.coerceIn(1, 100)
        val rows = jdbc.sql(
            """
            SELECT id, tx_type, amount, order_id::text AS order_id, memo, created_at FROM ledger_transaction
            WHERE member_id = :m AND brand_id = :b ${if (cursor != null) "AND id < :cursor" else ""}
            ORDER BY id DESC LIMIT :limit
            """.trimIndent(),
        )
            .param("m", memberId)
            .param("b", brandId)
            .param("limit", limit + 1)
            .apply { if (cursor != null) param("cursor", cursor) }
            .query { rs, _ ->
                PointHistoryItem(
                    rs.getLong("id"),
                    rs.getString("tx_type"),
                    rs.getLong("amount"),
                    rs.getString("order_id"),
                    rs.getString("memo"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }
            .list()
        val page = rows.take(limit)
        return PointHistoryPage(page, if (rows.size > limit) page.last().transactionId else null)
    }

    @Operation(summary = "[운영] 포인트 지급 (브랜드 부담)")
    @PostMapping("/admin/point-grants")
    @ResponseStatus(HttpStatus.CREATED)
    fun grant(
        @Valid @RequestBody request: GrantRequest,
    ): Map<String, Long> =
        mapOf("transactionId" to points.grant(request.memberId, request.brandId, request.amount, request.expiryDays, request.memo))
}

@Configuration
class LoyaltyTopics {
    @Bean
    fun loyaltyEventsTopic(): NewTopic = TopicBuilder.name(Topics.LOYALTY_EVENTS).partitions(3).build()
}
