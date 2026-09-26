package com.brewslot.order.ordering

import com.brewslot.order.OrderIntegrationTest
import com.brewslot.order.ordering.application.ExpiryJobs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaceOrderIntegrationTest : OrderIntegrationTest() {
    @Autowired lateinit var expiryJobs: ExpiryJobs

    private fun kst(date: String, time: String) = Instant.parse("${date}T$time:00+09:00")

    private fun orderBody(pickupAt: Instant, vararg items: Pair<Long, Int>, points: Long = 0) = mapOf(
        "storeId" to 101,
        "pickupAt" to pickupAt.toString(),
        "items" to items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) },
        "pointsToUse" to points,
    )

    private fun reserved(station: String, slotStart: Instant): Int = jdbc.sql(
        "SELECT COALESCE(SUM(reserved), 0) FROM slot_capacity WHERE store_id = 101 AND station = :s AND slot_start = :t",
    ).param("s", station).param("t", java.sql.Timestamp.from(slotStart)).query(Int::class.java).single()

    @Test
    fun `주문 생성은 제조 슬롯을 점유하고, 같은 Idempotency-Key 재요청은 같은 주문을 돌려준다`() {
        clock.set(kst("2026-10-01", "08:00"))
        val pickup = kst("2026-10-01", "08:30")
        val key = "key-" + UUID.randomUUID()

        val first = rest.postForEntity("/orders", entity(orderBody(pickup, 1003L to 2), 1, key), Map::class.java)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(first.body!!["status"]).isEqualTo("PENDING_PAYMENT")
        assertThat(first.headers.location.toString()).endsWith(first.body!!["orderId"].toString())
        assertThat(reserved("ESPRESSO", pickup.minusSeconds(300))).isEqualTo(4)

        val replay = rest.postForEntity("/orders", entity(orderBody(pickup, 1003L to 2), 1, key), Map::class.java)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(replay.headers.getFirst("Idempotent-Replayed")).isEqualTo("true")
        assertThat(replay.body!!["orderId"]).isEqualTo(first.body!!["orderId"])
        assertThat(reserved("ESPRESSO", pickup.minusSeconds(300))).isEqualTo(4)

        val misuse = rest.postForEntity("/orders", entity(orderBody(pickup, 1003L to 1), 1, key), Map::class.java)
        assertThat(misuse.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(misuse.body!!["type"].toString()).endsWith("idempotency-key-reused")
    }

    @Test
    fun `용량이 부족하면 409 와 함께 가능한 가장 가까운 픽업 시각을 제안한다`() {
        clock.set(kst("2026-10-02", "08:00"))
        val pickup = kst("2026-10-02", "08:30")
        rest.postForEntity("/orders", entity(orderBody(pickup, 1003L to 3), 1, "k-" + UUID.randomUUID()), Map::class.java)

        val rejected = rest.postForEntity("/orders", entity(orderBody(pickup, 1001L to 1), 2, "k-" + UUID.randomUUID()), Map::class.java)

        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(rejected.headers.contentType.toString()).contains("application/problem+json")
        val body = rejected.body!!
        assertThat(body["type"].toString()).endsWith("pickup-slot-unavailable")
        assertThat(body["bottleneckStation"]).isEqualTo("ESPRESSO")
        @Suppress("UNCHECKED_CAST")
        val alternatives = (body["alternatives"] as List<String>).map(Instant::parse)
        assertThat(alternatives).isNotEmpty.doesNotContain(pickup).hasSizeLessThanOrEqualTo(3)
        assertThat(alternatives).contains(kst("2026-10-02", "08:35"))
    }

    @Test
    fun `동시에 40명이 같은 시각을 주문해도 초과 예약은 0건이다`() {
        clock.set(kst("2026-10-03", "08:00"))
        val pickup = kst("2026-10-03", "09:00")
        val threads = 40
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val statuses = java.util.concurrent.ConcurrentLinkedQueue<HttpStatus>()
        repeat(threads) { i ->
            pool.submit {
                start.await()
                val res = rest.postForEntity(
                    "/orders",
                    entity(orderBody(pickup, 1003L to 1), 1000L + i, "c-" + UUID.randomUUID()),
                    Map::class.java,
                )
                statuses.add(HttpStatus.valueOf(res.statusCode.value()))
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

        // 핫 라떼(부하 2, 신선도 5분) → 08:55 슬롯 하나에만 만들 수 있고 에스프레소 용량은 6 → 정확히 3잔
        assertThat(statuses.count { it == HttpStatus.CREATED }).isEqualTo(3)
        assertThat(statuses.count { it == HttpStatus.CONFLICT }).isEqualTo(37)
        assertThat(reserved("ESPRESSO", pickup.minusSeconds(300))).isEqualTo(6)
    }

    @Test
    fun `결제를 시작하지 않은 주문은 점유 시간이 지나면 만료되고 용량이 돌아온다`() {
        clock.set(kst("2026-10-04", "08:00"))
        val pickup = kst("2026-10-04", "08:30")
        val created = rest.postForEntity("/orders", entity(orderBody(pickup, 1005L to 1), 7, "e-" + UUID.randomUUID()), Map::class.java)
        val orderId = created.body!!["orderId"].toString()
        assertThat(reserved("BLENDER", pickup.minusSeconds(300))).isEqualTo(3)

        clock.advance(Duration.ofMinutes(6))
        expiryJobs.expireHolds()

        val order = rest.exchange("/orders/$orderId", HttpMethod.GET, entity(null, 7), Map::class.java).body!!
        assertThat(order["status"]).isEqualTo("CANCELLED")
        assertThat(order["cancelReason"]).isEqualTo("HOLD_EXPIRED")
        assertThat(reserved("BLENDER", pickup.minusSeconds(300))).isEqualTo(0)
    }

    @Test
    fun `슬롯 경계가 아니거나 범위를 벗어난 픽업 시각은 거절한다`() {
        clock.set(kst("2026-10-05", "08:00"))
        val notAligned = rest.postForEntity(
            "/orders",
            entity(orderBody(kst("2026-10-05", "08:31"), 1001L to 1), 1, "v-" + UUID.randomUUID()),
            Map::class.java,
        )
        assertThat(notAligned.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val tooSoon = rest.postForEntity(
            "/orders",
            entity(orderBody(kst("2026-10-05", "08:00"), 1001L to 1), 1, "v-" + UUID.randomUUID()),
            Map::class.java,
        )
        assertThat(tooSoon.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(tooSoon.body!!["earliestPickupAt"]).isEqualTo(kst("2026-10-05", "08:05").toString())

        val soldOut = rest.postForEntity(
            "/orders",
            entity(orderBody(kst("2026-10-05", "08:30"), 1007L to 1), 1, "v-" + UUID.randomUUID()),
            Map::class.java,
        )
        assertThat(soldOut.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(soldOut.body!!["type"].toString()).endsWith("menu-item-unavailable")
    }

    @Test
    fun `장바구니 기준 픽업 가능 시각 조회`() {
        clock.set(kst("2026-10-06", "08:00"))
        val pickup = kst("2026-10-06", "08:30")
        rest.postForEntity("/orders", entity(orderBody(pickup, 1005L to 1), 1, "p-" + UUID.randomUUID()), Map::class.java)
        rest.postForEntity("/orders", entity(orderBody(pickup.minusSeconds(300), 1005L to 1), 2, "p-" + UUID.randomUUID()), Map::class.java)

        val res = rest.getForEntity(
            "/stores/101/pickup-times?items=1005:1&from={from}&to={to}",
            List::class.java,
            kst("2026-10-06", "08:20").toString(),
            kst("2026-10-06", "08:40").toString(),
        )

        @Suppress("UNCHECKED_CAST")
        val options = (res.body as List<Map<String, Any>>).associate { Instant.parse(it["pickupAt"].toString()) to it }
        // 스무디(창 10분): 08:25·08:20 슬롯이 가득 → 08:30 픽업 불가, 08:35 는 08:30 슬롯으로 가능
        assertThat(options[pickup]!!["feasible"]).isEqualTo(false)
        assertThat(options[pickup]!!["loadPercent"]).isEqualTo(100)
        assertThat(options[kst("2026-10-06", "08:35")]!!["feasible"]).isEqualTo(true)
    }
}
