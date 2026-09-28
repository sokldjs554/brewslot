package com.brewslot.order.catalog.infra

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.CapacityProfile
import com.brewslot.order.catalog.domain.MenuItem
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Store
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcStoreRepository(private val jdbc: JdbcClient) {
    fun storeIdsOfBrand(brandId: Long): List<Long> =
        jdbc.sql("SELECT id FROM store WHERE brand_id = :brandId ORDER BY id").param("brandId", brandId).query(Long::class.java).list()

    fun findById(storeId: Long): Store? {
        val header = jdbc.sql(
            """
            SELECT id, brand_id, name, open_time, close_time, slot_minutes, min_lead_minutes, max_advance_minutes
            FROM store WHERE id = :id
            """.trimIndent(),
        ).param("id", storeId).query { rs, _ ->
            StoreHeader(
                id = rs.getLong("id"),
                brandId = rs.getLong("brand_id"),
                name = rs.getString("name"),
                openTime = rs.getTime("open_time").toLocalTime(),
                closeTime = rs.getTime("close_time").toLocalTime(),
                slotMinutes = rs.getInt("slot_minutes"),
                minLead = rs.getInt("min_lead_minutes"),
                maxAdvance = rs.getInt("max_advance_minutes"),
            )
        }.optional().orElse(null) ?: return null

        val units = jdbc.sql("SELECT station, units_per_slot FROM store_station_capacity WHERE store_id = :id")
            .param("id", storeId)
            .query { rs, _ -> Station.valueOf(rs.getString("station")) to rs.getInt("units_per_slot") }
            .list()
            .toMap()

        val menu = jdbc.sql(
            """
            SELECT id, store_id, name, price, station, load_units, freshness_minutes, available
            FROM menu_item WHERE store_id = :id ORDER BY id
            """.trimIndent(),
        ).param("id", storeId).query { rs, _ ->
            MenuItem(
                id = rs.getLong("id"),
                storeId = rs.getLong("store_id"),
                name = rs.getString("name"),
                price = Money(rs.getLong("price")),
                station = Station.valueOf(rs.getString("station")),
                loadUnits = rs.getInt("load_units"),
                freshnessMinutes = rs.getInt("freshness_minutes"),
                available = rs.getBoolean("available"),
            )
        }.list()

        return Store(
            id = header.id,
            brandId = header.brandId,
            name = header.name,
            openTime = header.openTime,
            closeTime = header.closeTime,
            capacity = CapacityProfile(header.slotMinutes, units, header.minLead, header.maxAdvance),
            menu = menu,
        )
    }

    /**
     * 기본 용량을 바꾸고, 이미 생성된 미래 슬롯에도 반영한다.
     * 단, 이미 받은 예약보다 작게 줄일 수는 없다 — 용량을 줄여도 "이미 한 약속"은 지킨다: capacity = max(reserved, 새 용량)
     */
    fun updateStationCapacity(storeId: Long, unitsPerSlot: Map<Station, Int>, effectiveFrom: java.time.Instant) {
        unitsPerSlot.forEach { (station, units) ->
            jdbc.sql(
                """
                UPDATE slot_capacity SET capacity = GREATEST(reserved, :units), version = version + 1
                WHERE store_id = :storeId AND station = :station AND slot_start >= :from
                """.trimIndent(),
            ).param("storeId", storeId).param("station", station.name).param("units", units)
                .param("from", java.sql.Timestamp.from(effectiveFrom)).update()
            jdbc.sql(
                """
                INSERT INTO store_station_capacity (store_id, station, units_per_slot) VALUES (:storeId, :station, :units)
                ON CONFLICT (store_id, station) DO UPDATE SET units_per_slot = EXCLUDED.units_per_slot
                """.trimIndent(),
            ).param("storeId", storeId).param("station", station.name).param("units", units).update()
        }
    }

    private data class StoreHeader(
        val id: Long,
        val brandId: Long,
        val name: String,
        val openTime: java.time.LocalTime,
        val closeTime: java.time.LocalTime,
        val slotMinutes: Int,
        val minLead: Int,
        val maxAdvance: Int,
    )
}
