package com.brewslot.order.ordering.saga

import com.brewslot.common.Money
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcCheckoutSagaRepository(private val jdbc: JdbcClient) {
    fun insert(saga: CheckoutSaga, now: Instant) {
        jdbc.sql(
            """
            INSERT INTO checkout_saga (order_id, step, status, point_amount, card_amount, card_token, points_redeemed,
                                       started_at, deadline_at, updated_at, version)
            VALUES (:orderId, :step, :status, :points, :card, :token, :redeemed, :startedAt, :deadlineAt, :now, 0)
            """.trimIndent(),
        )
            .param("orderId", saga.orderId)
            .param("step", saga.step.name)
            .param("status", saga.status.name)
            .param("points", saga.pointAmount.won)
            .param("card", saga.cardAmount.won)
            .param("token", saga.cardToken)
            .param("redeemed", saga.pointsRedeemed)
            .param("startedAt", Timestamp.from(saga.startedAt))
            .param("deadlineAt", Timestamp.from(saga.deadlineAt))
            .param("now", Timestamp.from(now))
            .update()
    }

    fun update(saga: CheckoutSaga, now: Instant) {
        val n = jdbc.sql(
            """
            UPDATE checkout_saga SET step = :step, status = :status, points_redeemed = :redeemed, updated_at = :now,
                                     version = version + 1
            WHERE order_id = :orderId AND version = :version
            """.trimIndent(),
        )
            .param("step", saga.step.name)
            .param("status", saga.status.name)
            .param("redeemed", saga.pointsRedeemed)
            .param("now", Timestamp.from(now))
            .param("orderId", saga.orderId)
            .param("version", saga.version)
            .update()
        if (n != 1) throw OptimisticLockingFailureException("saga ${saga.orderId} modified concurrently")
    }

    fun find(orderId: UUID): CheckoutSaga? = jdbc.sql(
        """
        SELECT order_id, step, status, point_amount, card_amount, card_token, points_redeemed, started_at, deadline_at, version
        FROM checkout_saga WHERE order_id = :orderId
        """.trimIndent(),
    ).param("orderId", orderId).query { rs, _ ->
        CheckoutSaga(
            orderId = rs.getObject("order_id", UUID::class.java),
            pointAmount = Money(rs.getLong("point_amount")),
            cardAmount = Money(rs.getLong("card_amount")),
            cardToken = rs.getString("card_token"),
            step = SagaStep.valueOf(rs.getString("step")),
            status = SagaStatus.valueOf(rs.getString("status")),
            pointsRedeemed = rs.getBoolean("points_redeemed"),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            deadlineAt = rs.getTimestamp("deadline_at").toInstant(),
            version = rs.getLong("version"),
        )
    }.optional().orElse(null)

    /**
     * 타임아웃 후보 (잠그지 않음). 잠금은 처리 시 "주문 행 → Saga 행" 순서로만 잡는다.
     * 여기서 Saga 행을 먼저 잠그면, 주문 행을 먼저 잠그는 응답 처리와 잠금 순서가 엇갈려 교착상태가 생길 수 있다.
     */
    fun findTimedOut(now: Instant, limit: Int): List<UUID> = jdbc.sql(
        """
        SELECT order_id FROM checkout_saga
        WHERE status = 'RUNNING' AND deadline_at <= :now
        ORDER BY deadline_at
        LIMIT :limit
        """.trimIndent(),
    ).param("now", Timestamp.from(now)).param("limit", limit).query(UUID::class.java).list()
}
