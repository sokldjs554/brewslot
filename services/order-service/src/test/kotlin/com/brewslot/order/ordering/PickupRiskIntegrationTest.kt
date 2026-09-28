package com.brewslot.order.ordering

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.order.OrderIntegrationTest
import com.brewslot.order.ordering.application.PickupRiskDetector
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 사람들이 실제로 쓰는 매장 변경 (ADR-0014): 매장이 밀리면 먼저 알리고, 가까운 매장을 제안하고, 한 번 눌러 바로 옮긴다.
 * 역삼점(101) 기준 도보: 역삼역점(104, 자동 수락) 6분 · 삼성점(103) 13분 · 선릉점(102) 14분 · 판교점(105) 걸어갈 수 없음
 */
class PickupRiskIntegrationTest : OrderIntegrationTest() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    @Autowired lateinit var codec: EnvelopeCodec

    @Autowired lateinit var detector: PickupRiskDetector

    private fun kst(date: String, time: String) = Instant.parse("${date}T$time:00+09:00")

    private fun paidOrder(memberId: Long, pickupAt: Instant, vararg items: Pair<Long, Int>): String {
        val body = mapOf(
            "storeId" to 101,
            "pickupAt" to pickupAt.toString(),
            "items" to items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) },
            "pointsToUse" to 0,
        )
        val placed = rest.postForEntity("/orders", entity(body, memberId, "r-" + UUID.randomUUID()), Map::class.java)
        assertThat(placed.statusCode).isEqualTo(HttpStatus.CREATED)
        val orderId = placed.body!!["orderId"].toString()
        rest.postForEntity("/orders/$orderId/payment", entity(mapOf("cardToken" to "tok_visa"), memberId), Map::class.java)
        val total = (placed.body!!["totalAmount"] as Number).toLong()
        val captured = PaymentCaptured("pay-$orderId", orderId, memberId, 101, 1, total, "pg-$orderId", clock.instant())
        kafka.send(
            Topics.PAYMENT_EVENTS,
            orderId,
            codec.encode(EventEnvelope(UUID.randomUUID(), "PaymentCaptured", orderId, clock.instant(), orderId, null, codec.mapper.valueToTree(captured))),
        ).get()
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(order(memberId, orderId)["status"]).isEqualTo("PAID") }
        return orderId
    }

    private fun order(memberId: Long, orderId: String) =
        rest.exchange("/orders/$orderId", HttpMethod.GET, entity(null, memberId), Map::class.java).body!!

    private fun alerts(orderId: String) = jdbc.sql(
        "SELECT count(*) FROM outbox_event WHERE message_key = :k AND event_type = 'PickupAtRisk'",
    ).param("k", orderId).query(Int::class.java).single()

    @Test
    fun `매장이 밀리면 곧 받을 고객에게 먼저 알리고, 가까운 자동 수락 매장으로 한 번에 옮긴다`() {
        val date = "2026-11-10"
        clock.set(kst(date, "08:00"))
        paidOrder(41, kst(date, "08:30"), 1004L to 1) // 08:30 까지 준비돼야 하는 주문
        val waiting = paidOrder(42, kst(date, "08:55"), 1004L to 1, 1002L to 1)
        val dripOnly = paidOrder(43, kst(date, "09:00"), 1006L to 1) // 핸드드립: 근처 매장에 없음
        val far = paidOrder(44, kst(date, "10:30"), 1004L to 1) // 아직 먼 주문

        clock.set(kst(date, "08:33")) // 3분 밀림: 알리지 않는다
        detector.scan()
        assertThat(alerts(waiting)).isZero()

        clock.set(kst(date, "08:40")) // 매장이 첫 주문을 아직 못 냄 → 10분 밀림
        detector.scan()
        detector.scan() // 여러 번 돌아도 주문당 한 번만 알린다
        assertThat(alerts(waiting)).isEqualTo(1)
        assertThat(alerts(far)).isZero()

        val risk = order(42, waiting)["pickupRisk"] as Map<*, *>
        assertThat(risk["expectedDelayMinutes"]).isEqualTo(10)
        assertThat(risk["expectedReadyAt"]).isEqualTo(kst(date, "09:05").toString())
        val suggestion = risk["suggestion"] as Map<*, *>
        assertThat(suggestion["storeId"]).isEqualTo(104) // 가장 가까운(도보 6분) 매장, 같은 시각 그대로
        assertThat(suggestion["walkMinutes"]).isEqualTo(6)
        assertThat(suggestion["pickupAt"]).isEqualTo(kst(date, "08:55").toString())

        // 옮길 곳이 없어도 지연은 솔직하게 알린다
        val noMove = order(43, dripOnly)["pickupRisk"] as Map<*, *>
        assertThat(noMove["suggestion"]).isNull()

        // 한 번 누르면 자동 수락 매장이라 이 응답에서 바로 옮겨진다 (기다림 없음)
        val moved = rest.postForEntity(
            "/orders/$waiting/transfers",
            entity(mapOf("toStoreId" to 104, "pickupAt" to suggestion["pickupAt"]), 42, "one-tap-$waiting".take(60)),
            Map::class.java,
        )
        assertThat(moved.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(moved.body!!["status"]).isEqualTo("COMPLETED")
        val after = order(42, waiting)
        assertThat(after["storeId"]).isEqualTo(104)
        assertThat(after["pickupRisk"]).isNull() // 새 매장에서는 지연 안내가 사라진다
    }

    @Test
    fun `매장 변경 후보는 걸어갈 수 있는 매장만 가까운 순으로, 바로 옮겨지는 매장인지 함께 보여준다`() {
        val date = "2026-11-11"
        clock.set(kst(date, "08:00"))
        val orderId = paidOrder(45, kst(date, "08:40"), 1004L to 1)
        val options = rest.exchange("/orders/$orderId/transfer-options", HttpMethod.GET, entity(null, 45), Map::class.java).body!!
        val candidates = (options["candidates"] as List<*>).map { it as Map<*, *> }
        assertThat(candidates.map { it["storeId"] }).containsExactly(104, 103, 102) // 판교점(105)은 걸어갈 수 없어 제외
        assertThat(candidates.map { it["walkMinutes"] }).containsExactly(6, 13, 14)
        assertThat(candidates.map { it["instant"] }).containsExactly(true, false, false)
    }
}
