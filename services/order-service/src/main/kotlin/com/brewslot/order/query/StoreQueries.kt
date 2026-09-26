package com.brewslot.order.query

import com.brewslot.common.BusinessTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

data class QueuedDrink(val orderId: String, val menuName: String, val quantity: Int, val promisedPickupAt: Instant, val status: String)

data class ProductionSlot(val station: String, val slotStart: Instant, val reservedUnits: Int, val capacity: Int, val orders: List<String>)

data class StoreDashboard(
    val storeId: Long,
    val businessDate: LocalDate,
    val paidOrders: Int,
    val grossAmount: Long,
    val cancelledOrders: Int,
    val readyOrders: Int,
    /** 약속한 픽업 시각까지 음료가 준비된 비율. 이 서비스의 핵심 품질 지표. */
    val promiseKeptRate: Double?,
    val averageLatenessSeconds: Double?,
    val pickedUpOrders: Int,
    val slotUtilization: List<ProductionSlot>,
)

@Component
class StoreQueries(private val jdbc: JdbcClient) {
    /**
     * 바리스타 화면: 시간대별로 "무엇을 언제 만들어야 하는지". 픽업 순서가 아니라 제조 계획(슬롯) 순서다.
     */
    fun productionQueue(storeId: Long, from: Instant, to: Instant): List<ProductionSlot> {
        val lines = jdbc.sql(
            """
            SELECT l.station, l.slot_start, l.units, l.order_id::text AS order_id, c.capacity, c.reserved
            FROM slot_reservation_line l
            JOIN slot_reservation r ON r.order_id = l.order_id AND r.status = 'CONFIRMED'
            JOIN slot_capacity c ON c.store_id = l.store_id AND c.station = l.station AND c.slot_start = l.slot_start
            WHERE l.store_id = :storeId AND l.slot_start >= :from AND l.slot_start < :to
            ORDER BY l.slot_start, l.station
            """.trimIndent(),
        )
            .param("storeId", storeId)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query { rs, _ ->
                Line(
                    rs.getString("station"),
                    rs.getTimestamp("slot_start").toInstant(),
                    rs.getInt("units"),
                    rs.getString("order_id"),
                    rs.getInt("capacity"),
                    rs.getInt("reserved"),
                )
            }
            .list()
        return lines.groupBy { it.station to it.slotStart }.map { (key, ls) ->
            ProductionSlot(key.first, key.second, ls.first().reserved, ls.first().capacity, ls.map { it.orderId }.distinct())
        }.sortedWith(compareBy({ it.slotStart }, { it.station }))
    }

    fun dashboard(storeId: Long, date: LocalDate): StoreDashboard {
        val stats = jdbc.sql(
            """
            SELECT paid_orders, gross_amount, cancelled_orders, ready_orders, on_time_orders, total_lateness_seconds, picked_up_orders
            FROM store_daily_stats WHERE store_id = :storeId AND business_date = :date
            """.trimIndent(),
        ).param("storeId", storeId).param("date", date).query { rs, _ ->
            listOf(
                rs.getLong("paid_orders"),
                rs.getLong("gross_amount"),
                rs.getLong("cancelled_orders"),
                rs.getLong("ready_orders"),
                rs.getLong("on_time_orders"),
                rs.getLong("total_lateness_seconds"),
                rs.getLong("picked_up_orders"),
            )
        }.optional().orElse(List(7) { 0L })

        val utilization = jdbc.sql(
            """
            SELECT station, slot_start, reserved, capacity FROM slot_capacity
            WHERE store_id = :storeId AND slot_start >= :from AND slot_start < :to AND reserved > 0
            ORDER BY slot_start, station
            """.trimIndent(),
        )
            .param("storeId", storeId)
            .param("from", Timestamp.from(BusinessTime.startOf(date)))
            .param("to", Timestamp.from(BusinessTime.endExclusiveOf(date)))
            .query { rs, _ ->
                ProductionSlot(
                    rs.getString("station"),
                    rs.getTimestamp("slot_start").toInstant(),
                    rs.getInt("reserved"),
                    rs.getInt("capacity"),
                    emptyList(),
                )
            }
            .list()

        val ready = stats[3]
        return StoreDashboard(
            storeId = storeId,
            businessDate = date,
            paidOrders = stats[0].toInt(),
            grossAmount = stats[1],
            cancelledOrders = stats[2].toInt(),
            readyOrders = ready.toInt(),
            promiseKeptRate = if (ready > 0) stats[4].toDouble() / ready else null,
            averageLatenessSeconds = if (ready > 0) stats[5].toDouble() / ready else null,
            pickedUpOrders = stats[6].toInt(),
            slotUtilization = utilization,
        )
    }

    private data class Line(val station: String, val slotStart: Instant, val units: Int, val orderId: String, val capacity: Int, val reserved: Int)
}
