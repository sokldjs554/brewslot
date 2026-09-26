package com.brewslot.payment.fakepg

import com.brewslot.common.BusinessTime
import com.brewslot.common.Money
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

sealed interface PgApproval {
    data class Approved(val transactionId: String, val approvedAt: Instant) : PgApproval

    data class Declined(val reason: String) : PgApproval
}

/** PG 응답을 받지 못함. 승인이 됐는지 안 됐는지 "알 수 없다". */
class PgTimeoutException(orderId: UUID) : RuntimeException("PG 응답 시간 초과 (orderId=$orderId)")

data class PgRecord(val approveId: String, val amount: Long, val approvedAt: Instant, val cancelId: String?, val canceledAt: Instant?)

data class PgSettlementLine(
    val transactionId: String,
    val orderId: String,
    val kind: String,
    val amount: Long,
    val fee: Long,
    val transactedAt: Instant,
)

/** 우리 서비스가 PG 사와 대화하는 경계. 실제 연동(Toss Payments 등)은 이 인터페이스의 다른 구현이 된다. */
interface PgClient {
    fun approve(orderId: UUID, amount: Long, cardToken: String): PgApproval

    fun cancel(orderId: UUID): String

    fun lookup(orderId: UUID): PgRecord?
}

/**
 * 테스트·데모용 가짜 PG. 카드 토큰으로 시나리오를 고른다.
 * - tok_declined     : 승인 거절
 * - tok_insufficient : 한도 초과 거절
 * - tok_timeout      : PG 는 승인했지만 응답이 유실됨 (가장 까다로운 실제 장애 유형)
 * - 그 외             : 승인
 * 주문번호 기준 멱등(같은 주문을 두 번 승인해도 한 건)이다.
 */
@Component
class FakePgClient(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    @Value("\${brewslot.fake-pg.fee-bps:220}") private val feeBps: Int,
    @Value("\${brewslot.fake-pg.latency-ms:0}") private val latencyMs: Long,
) : PgClient {
    override fun approve(orderId: UUID, amount: Long, cardToken: String): PgApproval {
        if (latencyMs > 0) Thread.sleep(latencyMs)
        when (cardToken) {
            "tok_declined" -> return PgApproval.Declined("CARD_DECLINED")
            "tok_insufficient" -> return PgApproval.Declined("INSUFFICIENT_FUNDS")
        }
        val record = insert(orderId, "APPROVE", amount)
        if (cardToken == "tok_timeout") throw PgTimeoutException(orderId)
        return PgApproval.Approved(record.first, record.second)
    }

    override fun cancel(orderId: UUID): String {
        val approved = lookup(orderId) ?: error("no approval for $orderId")
        return approved.cancelId ?: insert(orderId, "CANCEL", approved.amount).first
    }

    override fun lookup(orderId: UUID): PgRecord? {
        val rows = jdbc.sql("SELECT id, kind, amount, transacted_at FROM fake_pg_transaction WHERE order_id = :orderId")
            .param("orderId", orderId)
            .query { rs, _ ->
                Triple(
                    rs.getString("kind"),
                    rs.getString("id"),
                    rs.getLong("amount") to rs.getTimestamp("transacted_at").toInstant(),
                )
            }
            .list()
        val approve = rows.firstOrNull { it.first == "APPROVE" } ?: return null
        val cancel = rows.firstOrNull { it.first == "CANCEL" }
        return PgRecord(approve.second, approve.third.first, approve.third.second, cancel?.second, cancel?.third?.second)
    }

    /** PG 사가 매일 내려주는 거래 대사 파일 (한국 시간 기준 거래일) */
    fun settlementFile(date: LocalDate): List<PgSettlementLine> = jdbc.sql(
        """
        SELECT id, order_id, kind, amount, fee, transacted_at FROM fake_pg_transaction
        WHERE transacted_at >= :from AND transacted_at < :to ORDER BY transacted_at, id
        """.trimIndent(),
    )
        .param("from", Timestamp.from(BusinessTime.startOf(date)))
        .param("to", Timestamp.from(BusinessTime.endExclusiveOf(date)))
        .query { rs, _ ->
            PgSettlementLine(
                rs.getString("id"),
                rs.getString("order_id"),
                rs.getString("kind"),
                rs.getLong("amount"),
                rs.getLong("fee"),
                rs.getTimestamp("transacted_at").toInstant(),
            )
        }
        .list()

    /** 대사(reconciliation) 데모용: 우리 시스템에는 없는 PG 거래를 만든다. */
    fun injectGhostApproval(amount: Long): String = insert(UUID.randomUUID(), "APPROVE", amount).first

    private fun insert(orderId: UUID, kind: String, amount: Long): Pair<String, Instant> {
        val now = clock.instant()
        val fee = Money(amount).percentOf(feeBps, RoundingMode.HALF_UP).won.let { if (kind == "CANCEL") -it else it }
        val signedAmount = if (kind == "CANCEL") -amount else amount
        val id = "pg_" + kind.lowercase() + "_" + UUID.randomUUID().toString().replace("-", "").take(20)
        jdbc.sql(
            """
            INSERT INTO fake_pg_transaction (id, order_id, kind, amount, fee, transacted_at)
            VALUES (:id, :orderId, :kind, :amount, :fee, :at)
            ON CONFLICT (order_id, kind) DO NOTHING
            """.trimIndent(),
        )
            .param("id", id)
            .param("orderId", orderId)
            .param("kind", kind)
            .param("amount", signedAmount)
            .param("fee", fee)
            .param("at", Timestamp.from(now))
            .update()
        return jdbc.sql("SELECT id, transacted_at FROM fake_pg_transaction WHERE order_id = :orderId AND kind = :kind")
            .param("orderId", orderId)
            .param("kind", kind)
            .query { rs, _ -> rs.getString("id") to rs.getTimestamp("transacted_at").toInstant() }
            .single()
    }
}
