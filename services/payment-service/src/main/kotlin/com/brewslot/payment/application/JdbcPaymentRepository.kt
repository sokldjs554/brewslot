package com.brewslot.payment.application

import com.brewslot.payment.domain.Payment
import com.brewslot.payment.domain.PaymentStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcPaymentRepository(private val jdbc: JdbcClient) {
    /** 주문당 1건. 이미 있으면 false. */
    fun insertRequested(p: Payment): Boolean = jdbc.sql(
        """
        INSERT INTO payment (id, order_id, member_id, store_id, brand_id, amount, card_token, status, created_at, updated_at)
        VALUES (:id, :orderId, :memberId, :storeId, :brandId, :amount, :token, 'REQUESTED', :now, :now)
        ON CONFLICT (order_id) DO NOTHING
        """.trimIndent(),
    )
        .param("id", p.id)
        .param("orderId", p.orderId)
        .param("memberId", p.memberId)
        .param("storeId", p.storeId)
        .param("brandId", p.brandId)
        .param("amount", p.amount)
        .param("token", p.cardToken)
        .param("now", Timestamp.from(p.createdAt))
        .update() == 1

    fun findByOrderId(orderId: UUID): Payment? = jdbc.sql("SELECT * FROM payment WHERE order_id = :orderId")
        .param("orderId", orderId)
        .query { rs, _ ->
            Payment(
                id = rs.getObject("id", UUID::class.java),
                orderId = rs.getObject("order_id", UUID::class.java),
                memberId = rs.getLong("member_id"),
                storeId = rs.getLong("store_id"),
                brandId = rs.getLong("brand_id"),
                amount = rs.getLong("amount"),
                cardToken = rs.getString("card_token"),
                status = PaymentStatus.valueOf(rs.getString("status")),
                pgTransactionId = rs.getString("pg_transaction_id"),
                pgCancelId = rs.getString("pg_cancel_id"),
                failureReason = rs.getString("failure_reason"),
                refundRequested = rs.getBoolean("refund_requested"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                capturedAt = rs.getTimestamp("captured_at")?.toInstant(),
                refundedAt = rs.getTimestamp("refunded_at")?.toInstant(),
            )
        }
        .optional().orElse(null)

    /** 상태 전이는 "기대하는 이전 상태" 조건부 UPDATE 로만 한다. 경쟁(복구 잡 vs 명령 처리) 시 한쪽만 성공한다. */
    fun markCaptured(orderId: UUID, pgTxId: String, at: Instant): Boolean = jdbc.sql(
        """
        UPDATE payment SET status = 'CAPTURED', pg_transaction_id = :tx, captured_at = :at, updated_at = :at
        WHERE order_id = :orderId AND status IN ('REQUESTED', 'UNKNOWN')
        """.trimIndent(),
    ).param("tx", pgTxId).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    fun markFailed(orderId: UUID, reason: String, at: Instant): Boolean = jdbc.sql(
        """
        UPDATE payment SET status = 'FAILED', failure_reason = :reason, updated_at = :at
        WHERE order_id = :orderId AND status IN ('REQUESTED', 'UNKNOWN')
        """.trimIndent(),
    ).param("reason", reason).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    fun markUnknown(orderId: UUID, at: Instant): Boolean = jdbc.sql(
        "UPDATE payment SET status = 'UNKNOWN', updated_at = :at WHERE order_id = :orderId AND status = 'REQUESTED'",
    ).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    fun markRefunded(orderId: UUID, cancelId: String, at: Instant): Boolean = jdbc.sql(
        """
        UPDATE payment SET status = 'REFUNDED', pg_cancel_id = :cancelId, refunded_at = :at, updated_at = :at
        WHERE order_id = :orderId AND status = 'CAPTURED'
        """.trimIndent(),
    ).param("cancelId", cancelId).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    fun requestRefund(orderId: UUID, at: Instant): Boolean = jdbc.sql(
        "UPDATE payment SET refund_requested = TRUE, updated_at = :at WHERE order_id = :orderId",
    ).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    /** 매장 변경된 주문: 이후 환불 이벤트가 현재 매장 기준으로 나가도록 귀속 매장을 바꾼다 (PG 거래는 그대로). */
    fun reassignStore(orderId: UUID, storeId: Long, at: Instant): Boolean = jdbc.sql(
        "UPDATE payment SET store_id = :storeId, updated_at = :at WHERE order_id = :orderId AND store_id <> :storeId",
    ).param("storeId", storeId).param("at", Timestamp.from(at)).param("orderId", orderId).update() == 1

    fun findUnresolved(olderThan: Instant, limit: Int): List<UUID> = jdbc.sql(
        """
        SELECT order_id FROM payment
        WHERE status IN ('REQUESTED', 'UNKNOWN') AND updated_at < :olderThan
        ORDER BY updated_at LIMIT :limit
        """.trimIndent(),
    ).param("olderThan", Timestamp.from(olderThan)).param("limit", limit).query(UUID::class.java).list()
}
