package com.brewslot.order.scheduling.infra

import com.brewslot.common.BusinessTime
import com.brewslot.order.catalog.domain.CapacityProfile
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.scheduling.domain.SlotKey
import com.brewslot.order.scheduling.domain.SlotState
import com.brewslot.order.scheduling.domain.SlotUsageSnapshot
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 가용 픽업시간 조회용 슬롯 사용량 캐시 (read-through + 커밋 후 갱신).
 *
 * 러시아워에는 "주문" 보다 "몇 시에 받을 수 있지?" 조회가 훨씬 많다. 이 조회가 슬롯 행 잠금과 경합하지 않도록
 * 읽기는 Redis 에서 처리하고, 예약의 정합성은 DB 에서만 판단한다. 캐시가 틀려도 결과는
 * "가능해 보였는데 주문 시 409 + 대안 시각 제안" 이므로 초과 예약으로 이어지지 않는다.
 *
 * 순서 역전 방지: 커밋 순서와 캐시 갱신 순서는 다를 수 있다(A 커밋 → B 커밋 → B 캐시 → A 캐시).
 * 그래서 값에 slot_capacity.version 을 함께 저장하고, Lua 스크립트로 "더 새 버전일 때만" 덮어쓴다.
 */
@Component
class RedisSlotUsageCache(
    private val redis: StringRedisTemplate,
    private val ledger: JdbcCapacityLedger,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val hits = meterRegistry.counter("brewslot.slot.cache", "result", "hit")
    private val rebuilds = meterRegistry.counter("brewslot.slot.cache", "result", "rebuild")
    private val fallbacks = meterRegistry.counter("brewslot.slot.cache", "result", "fallback")

    fun snapshot(storeId: Long, profile: CapacityProfile, from: Instant, toExclusive: Instant): SlotUsageSnapshot = try {
        val states = HashMap<SlotKey, SlotState>()
        datesBetween(from, toExclusive).forEach { date ->
            val key = hashKey(storeId, date)
            if (redis.opsForHash<String, String>().hasKey(key, LOADED_FIELD) != true) {
                rebuild(storeId, date)
                rebuilds.increment()
            } else {
                hits.increment()
            }
            val entries = redis.opsForHash<String, String>().entries(key)
            entries.forEach { (field, value) ->
                if (field == LOADED_FIELD) return@forEach
                val slotKey = parseField(field)
                if (!slotKey.slotStart.isBefore(from) && slotKey.slotStart.isBefore(toExclusive)) {
                    val (capacity, reserved) = value.split(':').let { it[0].toInt() to it[1].toInt() }
                    states[slotKey] = SlotState(capacity, reserved)
                }
            }
        }
        SlotUsageSnapshot(states) { profile.unitsOf(it) }
    } catch (e: Exception) {
        // Redis 장애는 조회 기능을 멈추게 하지 않는다. DB 로 우회한다.
        fallbacks.increment()
        log.warn("slot cache unavailable, falling back to DB: {}", e.toString())
        ledger.snapshot(storeId, profile, from, toExclusive)
    }

    /** 커밋된 최신 행 값을 반영한다. 캐시 갱신 실패는 삼킨다(다음 조회 때 재구성). */
    fun write(storeId: Long, rows: List<SlotRow>) {
        runCatching {
            rows.groupBy { BusinessTime.businessDateOf(it.key.slotStart) }.forEach { (date, dayRows) ->
                val args = mutableListOf(TTL.seconds.toString())
                dayRows.forEach { r ->
                    args += field(r.key)
                    args += "${r.capacity}:${r.reserved}:${r.version}"
                    args += r.version.toString()
                }
                redis.execute(CAS_SCRIPT, listOf(hashKey(storeId, date)), *args.toTypedArray())
            }
        }.onFailure { log.warn("slot cache write failed (ignored): {}", it.toString()) }
    }

    fun evictStore(storeId: Long, dates: Collection<LocalDate>) {
        runCatching { redis.delete(dates.map { hashKey(storeId, it) }) }
    }

    private fun rebuild(storeId: Long, date: LocalDate) {
        val rows = ledger.rows(storeId, BusinessTime.startOf(date), BusinessTime.endExclusiveOf(date))
        write(storeId, rows)
        val key = hashKey(storeId, date)
        redis.opsForHash<String, String>().put(key, LOADED_FIELD, "1")
        redis.expire(key, TTL)
    }

    private fun datesBetween(from: Instant, toExclusive: Instant): List<LocalDate> {
        val first = BusinessTime.businessDateOf(from)
        val last = BusinessTime.businessDateOf(toExclusive.minusNanos(1))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    private fun hashKey(storeId: Long, date: LocalDate) = "slot-usage:$storeId:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"

    private fun field(key: SlotKey) = "${key.station.name}:${key.slotStart.epochSecond}"

    private fun parseField(field: String): SlotKey {
        val (station, epoch) = field.split(':')
        return SlotKey(Station.valueOf(station), Instant.ofEpochSecond(epoch.toLong()))
    }

    companion object {
        private const val LOADED_FIELD = "__loaded"
        private val TTL: Duration = Duration.ofHours(36)

        /** KEYS[1]=hash, ARGV[1]=ttl, 이후 (field, value, version) 반복. 더 새 버전만 기록한다. */
        private val CAS_SCRIPT = DefaultRedisScript(
            """
            local ttl = tonumber(ARGV[1])
            for i = 2, #ARGV, 3 do
              local field = ARGV[i]
              local value = ARGV[i + 1]
              local ver = tonumber(ARGV[i + 2])
              local cur = redis.call('HGET', KEYS[1], field)
              local curVer = -1
              if cur then curVer = tonumber(string.match(cur, ':(%d+)$')) end
              if ver > curVer then redis.call('HSET', KEYS[1], field, value) end
            end
            redis.call('EXPIRE', KEYS[1], ttl)
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
