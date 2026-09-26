package com.brewslot.loyalty.application

import com.brewslot.loyalty.domain.Consumption
import com.brewslot.loyalty.domain.Lot
import com.brewslot.loyalty.domain.Posting
import com.brewslot.loyalty.domain.TxType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class LedgerTx(
    val id: Long,
    val type: TxType,
    val orderId: UUID?,
    val memberId: Long,
    val brandId: Long,
    val storeId: Long?,
    val amount: Long,
    val memo: String?,
    val createdAt: Instant,
)

@Repository
class PointLedger(private val jdbc: JdbcClient) {
    fun record(
        type: TxType,
        orderId: UUID?,
        memberId: Long,
        brandId: Long,
        storeId: Long?,
        amount: Long,
        memo: String?,
        posting: Posting,
        now: Instant,
    ): Long {
        val txId = jdbc.sql(
            """
            INSERT INTO ledger_transaction (tx_type, order_id, member_id, brand_id, store_id, amount, memo, created_at)
            VALUES (:type, :orderId, :memberId, :brandId, :storeId, :amount, :memo, :now)
            RETURNING id
            """.trimIndent(),
        )
            .param("type", type.name)
            .param("orderId", orderId)
            .param("memberId", memberId)
            .param("brandId", brandId)
            .param("storeId", storeId)
            .param("amount", amount)
            .param("memo", memo)
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .single()
        posting.entries.forEach { e ->
            jdbc.sql("INSERT INTO ledger_entry (transaction_id, account, direction, amount) VALUES (:tx, :account, :dir, :amount)")
                .param("tx", txId)
                .param("account", e.account)
                .param("dir", e.direction.name)
                .param("amount", e.amount)
                .update()
        }
        return txId
    }

    fun findByOrder(orderId: UUID, type: TxType): LedgerTx? = jdbc.sql(
        "SELECT * FROM ledger_transaction WHERE order_id = :orderId AND tx_type = :type",
    ).param("orderId", orderId).param("type", type.name).query { rs, _ ->
        LedgerTx(
            rs.getLong("id"),
            TxType.valueOf(rs.getString("tx_type")),
            rs.getObject("order_id", UUID::class.java),
            rs.getLong("member_id"),
            rs.getLong("brand_id"),
            rs.getObject("store_id") as Long?,
            rs.getLong("amount"),
            rs.getString("memo"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }.optional().orElse(null)

    /** 지갑 행을 잠근다(없으면 만든다). 같은 (회원, 브랜드)의 적립·사용·소멸이 직렬화된다. */
    fun lockWallet(memberId: Long, brandId: Long, now: Instant): Long {
        jdbc.sql(
            """
            INSERT INTO point_wallet (member_id, brand_id, balance, updated_at) VALUES (:m, :b, 0, :now)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("m", memberId).param("b", brandId).param("now", Timestamp.from(now)).update()
        return jdbc.sql("SELECT balance FROM point_wallet WHERE member_id = :m AND brand_id = :b FOR UPDATE")
            .param("m", memberId).param("b", brandId).query(Long::class.java).single()
    }

    fun adjustWallet(memberId: Long, brandId: Long, delta: Long, now: Instant) {
        jdbc.sql("UPDATE point_wallet SET balance = balance + :d, updated_at = :now WHERE member_id = :m AND brand_id = :b")
            .param("d", delta).param("now", Timestamp.from(now)).param("m", memberId).param("b", brandId).update()
    }

    fun createLot(memberId: Long, brandId: Long, sourceTxId: Long, amount: Long, earnedAt: Instant, expiresAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO point_lot (member_id, brand_id, source_tx_id, original_amount, remaining, earned_at, expires_at)
            VALUES (:m, :b, :tx, :amount, :amount, :earnedAt, :expiresAt)
            """.trimIndent(),
        )
            .param("m", memberId)
            .param("b", brandId)
            .param("tx", sourceTxId)
            .param("amount", amount)
            .param("earnedAt", Timestamp.from(earnedAt))
            .param("expiresAt", Timestamp.from(expiresAt))
            .update()
    }

    fun usableLots(memberId: Long, brandId: Long, now: Instant): List<Lot> = jdbc.sql(
        """
        SELECT id, remaining, expires_at FROM point_lot
        WHERE member_id = :m AND brand_id = :b AND remaining > 0 AND expires_at > :now
        ORDER BY expires_at, id
        """.trimIndent(),
    ).param("m", memberId).param("b", brandId).param("now", Timestamp.from(now))
        .query { rs, _ -> Lot(rs.getLong("id"), rs.getLong("remaining"), rs.getTimestamp("expires_at").toInstant()) }
        .list()

    fun consume(txId: Long, consumptions: List<Consumption>) {
        consumptions.forEach { c ->
            jdbc.sql("UPDATE point_lot SET remaining = remaining - :a WHERE id = :id").param("a", c.amount).param("id", c.lotId).update()
            jdbc.sql("INSERT INTO lot_consumption (transaction_id, lot_id, amount) VALUES (:tx, :lot, :a)")
                .param("tx", txId).param("lot", c.lotId).param("a", c.amount).update()
        }
    }

    fun consumptionsOf(txId: Long): List<Consumption> =
        jdbc.sql("SELECT lot_id, amount FROM lot_consumption WHERE transaction_id = :tx")
            .param("tx", txId).query { rs, _ -> Consumption(rs.getLong("lot_id"), rs.getLong("amount")) }.list()

    fun restore(consumptions: List<Consumption>) {
        consumptions.forEach { c ->
            jdbc.sql("UPDATE point_lot SET remaining = remaining + :a WHERE id = :id").param("a", c.amount).param("id", c.lotId).update()
        }
    }

    fun policy(brandId: Long): Pair<Int, Int> = jdbc.sql("SELECT earn_rate_bps, expiry_days FROM brand_point_policy WHERE brand_id = :b")
        .param("b", brandId).query { rs, _ -> rs.getInt("earn_rate_bps") to rs.getInt("expiry_days") }
        .optional().orElse(DEFAULT_POLICY)

    /** 소멸 후보 지갑 (잠그지 않음 — 실제 처리는 지갑 잠금 후 재확인) */
    fun walletsWithExpiredLots(now: Instant, limit: Int): List<Pair<Long, Long>> = jdbc.sql(
        """
        SELECT DISTINCT member_id, brand_id FROM (
            SELECT member_id, brand_id FROM point_lot
            WHERE remaining > 0 AND expires_at <= :now
            ORDER BY expires_at LIMIT :limit
        ) t
        """.trimIndent(),
    ).param("now", Timestamp.from(now)).param("limit", limit)
        .query { rs, _ -> rs.getLong("member_id") to rs.getLong("brand_id") }
        .list()

    fun expiredLotsForUpdate(memberId: Long, brandId: Long, now: Instant): List<Lot> = jdbc.sql(
        """
        SELECT id, remaining, expires_at FROM point_lot
        WHERE member_id = :m AND brand_id = :b AND remaining > 0 AND expires_at <= :now
        ORDER BY id FOR UPDATE
        """.trimIndent(),
    ).param("m", memberId).param("b", brandId).param("now", Timestamp.from(now))
        .query { rs, _ -> Lot(rs.getLong("id"), rs.getLong("remaining"), rs.getTimestamp("expires_at").toInstant()) }
        .list()

    fun zeroLot(lotId: Long) {
        jdbc.sql("UPDATE point_lot SET remaining = 0 WHERE id = :id").param("id", lotId).update()
    }

    companion object {
        val DEFAULT_POLICY = 100 to 365
    }
}
