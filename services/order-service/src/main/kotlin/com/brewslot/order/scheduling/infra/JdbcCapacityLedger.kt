package com.brewslot.order.scheduling.infra

import com.brewslot.order.catalog.domain.CapacityProfile
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.scheduling.domain.Allocation
import com.brewslot.order.scheduling.domain.SlotKey
import com.brewslot.order.scheduling.domain.SlotState
import com.brewslot.order.scheduling.domain.SlotUsageSnapshot
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 슬롯 한 칸의 최신 값. Redis 캐시 갱신(버전 비교)에 쓰인다. */
data class SlotRow(val key: SlotKey, val capacity: Int, val reserved: Int, val version: Long)

/**
 * 제조 용량의 원천(source of truth). PostgreSQL 행 잠금으로 초과 예약을 막는다.
 *
 * 동시성 전략 (docs/adr/0002-slot-capacity-concurrency.md):
 *  1. 필요한 슬롯 행을 별도 짧은 트랜잭션에서 미리 만든다 (INSERT .. ON CONFLICT DO NOTHING).
 *  2. 본 트랜잭션에서 ORDER BY station, slot_start .. FOR UPDATE — 모든 요청이 같은 순서로 잠그므로 교착상태가 없다.
 *     (PostgreSQL 은 LockRows 노드가 Sort 위에 있어 정렬된 순서대로 행을 잠근다)
 *  3. 잠근 뒤 잔여 용량을 다시 확인하고, 모자라면 false 를 돌려 호출자가 최신 스냅샷으로 재계획하게 한다.
 *  4. 마지막 방어선으로 CHECK (reserved <= capacity) 제약이 있다.
 */
@Repository
class JdbcCapacityLedger(
    private val jdbc: JdbcClient,
    transactionManager: PlatformTransactionManager,
) {
    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun snapshot(storeId: Long, profile: CapacityProfile, from: Instant, toExclusive: Instant): SlotUsageSnapshot {
        val states = rows(storeId, from, toExclusive).associate { it.key to SlotState(it.capacity, it.reserved) }
        return SlotUsageSnapshot(states) { profile.unitsOf(it) }
    }

    fun rows(storeId: Long, from: Instant, toExclusive: Instant): List<SlotRow> = jdbc.sql(
        """
        SELECT station, slot_start, capacity, reserved, version
        FROM slot_capacity
        WHERE store_id = :storeId AND slot_start >= :from AND slot_start < :to
        """.trimIndent(),
    )
        .param("storeId", storeId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(toExclusive))
        .query { rs, _ ->
            SlotRow(
                SlotKey(Station.valueOf(rs.getString("station")), rs.getTimestamp("slot_start").toInstant()),
                rs.getInt("capacity"),
                rs.getInt("reserved"),
                rs.getLong("version"),
            )
        }
        .list()

    fun rowsFor(storeId: Long, keys: Collection<SlotKey>): List<SlotRow> {
        if (keys.isEmpty()) return emptyList()
        val (sql, params) = keyPredicate(keys)
        return jdbc.sql("SELECT station, slot_start, capacity, reserved, version FROM slot_capacity WHERE store_id = :storeId AND ($sql)")
            .param("storeId", storeId)
            .params(params)
            .query { rs, _ ->
                SlotRow(
                    SlotKey(Station.valueOf(rs.getString("station")), rs.getTimestamp("slot_start").toInstant()),
                    rs.getInt("capacity"),
                    rs.getInt("reserved"),
                    rs.getLong("version"),
                )
            }
            .list()
    }

    /** 슬롯 행을 미리 만든다. 커밋 후 반환되므로 이후 본 트랜잭션은 순수하게 UPDATE 경합만 겪는다. */
    fun ensureSlots(storeId: Long, profile: CapacityProfile, allocations: List<Allocation>) {
        requiresNew.executeWithoutResult {
            allocations.sortedWith(compareBy({ it.station.ordinal }, { it.slotStart })).forEach { a ->
                jdbc.sql(
                    """
                    INSERT INTO slot_capacity (store_id, station, slot_start, capacity, reserved)
                    VALUES (:storeId, :station, :slotStart, :capacity, 0)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
                    .param("storeId", storeId)
                    .param("station", a.station.name)
                    .param("slotStart", Timestamp.from(a.slotStart))
                    .param("capacity", profile.unitsOf(a.station))
                    .update()
            }
        }
    }

    /**
     * 현재 트랜잭션 안에서 할당을 반영한다. 잔여 용량이 모자라면 아무것도 바꾸지 않고 false.
     * 반드시 [ensureSlots] 이후, 호출자의 트랜잭션 안에서 불러야 한다.
     */
    fun tryReserve(storeId: Long, orderId: UUID, allocations: List<Allocation>, now: Instant): Boolean {
        val ordered = allocations.sortedWith(compareBy({ it.station.ordinal }, { it.slotStart }))
        val locked = lockInOrder(storeId, ordered.map { it.key })
        val enough = ordered.all { a -> locked[a.key]?.let { it.remaining >= a.units } ?: false }
        if (!enough) return false

        ordered.forEach { a -> adjust(storeId, a, +a.units) }
        jdbc.sql(
            """
            INSERT INTO slot_reservation (order_id, store_id, status, created_at, updated_at)
            VALUES (:orderId, :storeId, 'HELD', :now, :now)
            """.trimIndent(),
        ).param("orderId", orderId).param("storeId", storeId).param("now", Timestamp.from(now)).update()
        ordered.forEach { a ->
            jdbc.sql(
                """
                INSERT INTO slot_reservation_line (order_id, store_id, station, slot_start, units)
                VALUES (:orderId, :storeId, :station, :slotStart, :units)
                """.trimIndent(),
            )
                .param("orderId", orderId)
                .param("storeId", storeId)
                .param("station", a.station.name)
                .param("slotStart", Timestamp.from(a.slotStart))
                .param("units", a.units)
                .update()
        }
        return true
    }

    fun confirm(orderId: UUID, now: Instant): Boolean = jdbc.sql(
        "UPDATE slot_reservation SET status = 'CONFIRMED', updated_at = :now WHERE order_id = :orderId AND status = 'HELD'",
    ).param("orderId", orderId).param("now", Timestamp.from(now)).update() == 1

    /** 예약을 해제하고 용량을 돌려준다. 이미 해제됐으면 빈 목록(멱등). 영향받은 슬롯 키를 반환한다. */
    fun release(orderId: UUID, now: Instant): List<SlotKey> {
        val released = jdbc.sql(
            """
            UPDATE slot_reservation SET status = 'RELEASED', updated_at = :now
            WHERE order_id = :orderId AND status IN ('HELD', 'CONFIRMED')
            RETURNING store_id
            """.trimIndent(),
        ).param("orderId", orderId).param("now", Timestamp.from(now)).query(Long::class.java).optional()
        if (released.isEmpty) return emptyList()
        val storeId = released.get()

        val lines = jdbc.sql("SELECT station, slot_start, units FROM slot_reservation_line WHERE order_id = :orderId")
            .param("orderId", orderId)
            .query { rs, _ ->
                Allocation(Station.valueOf(rs.getString("station")), rs.getTimestamp("slot_start").toInstant(), rs.getInt("units"))
            }
            .list()
            .sortedWith(compareBy({ it.station.ordinal }, { it.slotStart }))
        lockInOrder(storeId, lines.map { it.key })
        lines.forEach { adjust(storeId, it, -it.units) }
        return lines.map { it.key }
    }

    /**
     * 매장 변경 확정: [fromKey] 로 잡아 둔 (새 매장) 예약을 [toKey](주문 ID) 로 넘기고 CONFIRMED 로 만든다.
     * 슬롯 용량(reserved)은 이미 반영돼 있으므로 건드리지 않는다. 원래 매장 예약은 호출자가 먼저 [release] 해야 한다.
     * @return 넘긴 예약이 있었으면 true
     */
    fun handOver(fromKey: UUID, toKey: UUID, now: Instant): Boolean {
        val storeId = jdbc.sql("SELECT store_id FROM slot_reservation WHERE order_id = :from AND status = 'HELD' FOR UPDATE")
            .param("from", fromKey).query(Long::class.java).optional().orElse(null) ?: return false
        // 원래 매장 예약 기록(이미 RELEASED)은 주문 ID 를 비워 주기 위해 지운다. 변경 이력은 store_transfer 에 남는다.
        jdbc.sql("DELETE FROM slot_reservation_line WHERE order_id = :to").param("to", toKey).update()
        jdbc.sql("DELETE FROM slot_reservation WHERE order_id = :to AND status = 'RELEASED'").param("to", toKey).update()
        jdbc.sql(
            """
            INSERT INTO slot_reservation (order_id, store_id, status, created_at, updated_at)
            VALUES (:to, :storeId, 'CONFIRMED', :now, :now)
            """.trimIndent(),
        ).param("to", toKey).param("storeId", storeId).param("now", Timestamp.from(now)).update()
        jdbc.sql("UPDATE slot_reservation_line SET order_id = :to WHERE order_id = :from").param("to", toKey).param("from", fromKey).update()
        jdbc.sql("DELETE FROM slot_reservation WHERE order_id = :from").param("from", fromKey).update()
        return true
    }

    private fun lockInOrder(storeId: Long, keys: List<SlotKey>): Map<SlotKey, SlotState> {
        val (sql, params) = keyPredicate(keys)
        return jdbc.sql(
            """
            SELECT station, slot_start, capacity, reserved FROM slot_capacity
            WHERE store_id = :storeId AND ($sql)
            ORDER BY station, slot_start
            FOR UPDATE
            """.trimIndent(),
        )
            .param("storeId", storeId)
            .params(params)
            .query { rs, _ ->
                SlotKey(Station.valueOf(rs.getString("station")), rs.getTimestamp("slot_start").toInstant()) to
                    SlotState(rs.getInt("capacity"), rs.getInt("reserved"))
            }
            .list()
            .toMap()
    }

    private fun adjust(storeId: Long, a: Allocation, delta: Int) {
        jdbc.sql(
            """
            UPDATE slot_capacity SET reserved = reserved + :delta, version = version + 1
            WHERE store_id = :storeId AND station = :station AND slot_start = :slotStart
            """.trimIndent(),
        )
            .param("delta", delta)
            .param("storeId", storeId)
            .param("station", a.station.name)
            .param("slotStart", Timestamp.from(a.slotStart))
            .update()
    }

    private fun keyPredicate(keys: Collection<SlotKey>): Pair<String, Map<String, Any>> {
        val params = mutableMapOf<String, Any>()
        val sql = keys.mapIndexed { i, k ->
            params["st$i"] = k.station.name
            params["ts$i"] = Timestamp.from(k.slotStart)
            "(station = :st$i AND slot_start = :ts$i)"
        }.joinToString(" OR ")
        return sql to params
    }
}
