package com.brewslot.order.ordering

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.order.OrderIntegrationTest
import com.brewslot.order.ordering.application.StoreTransferService
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 매장 변경 (ADR-0013). 어떤 결과가 나와도 "원래 주문을 잃지 않는다" 와 "슬롯 용량이 새지 않는다" 를 확인한다.
 * 역삼점(101) → 삼성점(103, 같은 메뉴 · 같은 가격) / 선릉점(102, 일부 메뉴 없음 · 용량 작음)
 */
class StoreTransferIntegrationTest : OrderIntegrationTest() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    @Autowired lateinit var codec: EnvelopeCodec

    @Autowired lateinit var transfers: StoreTransferService

    private fun kst(date: String, time: String) = Instant.parse("${date}T$time:00+09:00")

    private fun items(vararg items: Pair<Long, Int>) = items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) }

    private fun place(memberId: Long, pickupAt: Instant, vararg items: Pair<Long, Int>, storeId: Long = 101): ResponseEntity<Map<*, *>> {
        val body = mapOf("storeId" to storeId, "pickupAt" to pickupAt.toString(), "items" to items(*items), "pointsToUse" to 0)
        return rest.postForEntity("/orders", entity(body, memberId, "t-" + UUID.randomUUID()), Map::class.java) as ResponseEntity<Map<*, *>>
    }

    /** 결제 완료(PAID)된 주문. 카드만 쓰므로 Saga 는 ChargePayment → PaymentCaptured 한 번. */
    private fun paidOrder(memberId: Long, pickupAt: Instant, vararg items: Pair<Long, Int>): String {
        val placed = place(memberId, pickupAt, *items)
        assertThat(placed.statusCode).isEqualTo(HttpStatus.CREATED)
        val orderId = placed.body!!["orderId"].toString()
        val total = (placed.body!!["totalAmount"] as Number).toLong()
        rest.postForEntity("/orders/$orderId/payment", entity(mapOf("cardToken" to "tok_visa"), memberId), Map::class.java)
        val captured = PaymentCaptured("pay-$orderId", orderId, memberId, 101, 1, total, "pg-$orderId", clock.instant())
        val envelope = EventEnvelope(UUID.randomUUID(), "PaymentCaptured", orderId, clock.instant(), orderId, null, codec.mapper.valueToTree(captured))
        kafka.send(Topics.PAYMENT_EVENTS, orderId, codec.encode(envelope)).get()
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(order(memberId, orderId)["status"]).isEqualTo("PAID") }
        return orderId
    }

    private fun order(memberId: Long, orderId: String) =
        rest.exchange("/orders/$orderId", HttpMethod.GET, entity(null, memberId), Map::class.java).body!!

    private fun requestTransfer(memberId: Long, orderId: String, toStoreId: Long, pickupAt: Instant, key: String = "tr-" + UUID.randomUUID()) =
        rest.postForEntity(
            "/orders/$orderId/transfers",
            entity(mapOf("toStoreId" to toStoreId, "pickupAt" to pickupAt.toString()), memberId, key),
            Map::class.java,
        )

    private fun accept(storeId: Long, transferId: String) =
        rest.postForEntity("/stores/$storeId/transfer-requests/$transferId/acceptance", entity(null, 0), Map::class.java)

    private fun refuse(storeId: Long, transferId: String) =
        rest.postForEntity("/stores/$storeId/transfer-requests/$transferId/refusal", entity(null, 0), Map::class.java)

    private fun transfer(memberId: Long, orderId: String, transferId: String) =
        rest.exchange("/orders/$orderId/transfers/$transferId", HttpMethod.GET, entity(null, memberId), Map::class.java).body!!

    private fun storeAction(storeId: Long, orderId: String, status: String) = rest.exchange(
        "/stores/$storeId/orders/$orderId/status",
        HttpMethod.PUT,
        entity(mapOf("status" to status), 0),
        Map::class.java,
    )

    /** 그 날 그 매장 슬롯에 잡혀 있는 제조 부하 합계 */
    private fun reserved(storeId: Long, date: String): Int = jdbc.sql(
        "SELECT COALESCE(SUM(reserved), 0) FROM slot_capacity WHERE store_id = :s AND slot_start >= :from AND slot_start < :to",
    )
        .param("s", storeId)
        .param("from", java.sql.Timestamp.from(kst(date, "00:00")))
        .param("to", java.sql.Timestamp.from(kst(date, "23:59")))
        .query(Int::class.java)
        .single()

    private fun outboxTypes(orderId: String): List<String> = jdbc.sql(
        "SELECT event_type FROM outbox_event WHERE message_key = :k AND topic = 'order.events' ORDER BY id",
    ).param("k", orderId).query(String::class.java).list()

    @Test
    fun `새 매장이 수락하면 한 트랜잭션에서 원래 자리를 풀고 새 자리를 확정해 주문을 옮긴다`() {
        val date = "2026-11-01"
        clock.set(kst(date, "08:00"))
        val pickup = kst(date, "08:40")
        val orderId = paidOrder(31, pickup, 1004L to 2, 1002L to 1) // 아이스 바닐라라떼 2 + 아이스 아메리카노 1 = 13,500원
        assertThat(reserved(101, date)).isEqualTo(5)

        val requested = requestTransfer(31, orderId, 103, kst(date, "08:45"))
        assertThat(requested.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val transferId = requested.body!!["transferId"].toString()
        assertThat(requested.body!!["status"]).isEqualTo("AWAITING_STORE")
        // 수락 전: 원래 자리와 새 자리를 모두 잡고 있다. 원래 주문은 원래 매장에서 그대로 유효
        assertThat(reserved(101, date)).isEqualTo(5)
        assertThat(reserved(103, date)).isEqualTo(5)
        assertThat(order(31, orderId)["storeId"]).isEqualTo(101)
        val pending = rest.getForObject("/stores/103/transfer-requests", List::class.java)
        assertThat(pending.map { (it as Map<*, *>)["transferId"] }).contains(transferId)

        clock.set(kst(date, "08:01"))
        val accepted = accept(103, transferId)
        assertThat(accepted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(accepted.body!!["status"]).isEqualTo("COMPLETED")
        // 태블릿이 응답을 못 받고 다시 눌러도 같은 결과
        assertThat(accept(103, transferId).body!!["status"]).isEqualTo("COMPLETED")

        val moved = order(31, orderId)
        assertThat(moved["storeId"]).isEqualTo(103)
        assertThat(moved["status"]).isEqualTo("PAID")
        assertThat(moved["promisedPickupAt"]).isEqualTo(kst(date, "08:45").toString())
        assertThat(moved["totalAmount"]).isEqualTo(13500)
        assertThat((moved["lines"] as List<*>).map { (it as Map<*, *>)["menuItemId"] }).containsExactly(4004, 4002)
        assertThat(reserved(101, date)).isZero()
        assertThat(reserved(103, date)).isEqualTo(5)
        assertThat(
            jdbc.sql("SELECT store_id || ':' || status FROM slot_reservation WHERE order_id = CAST(:id AS uuid)")
                .param("id", orderId).query(String::class.java).single(),
        ).isEqualTo("103:CONFIRMED")
        assertThat(jdbc.sql("SELECT count(*) FROM slot_reservation WHERE order_id = CAST(:id AS uuid)").param("id", transferId).query(Int::class.java).single())
            .isZero()
        assertThat(outboxTypes(orderId)).contains("OrderTransferred")

        // 새 매장 제조 큐에 보이고, 원래 매장은 더 이상 이 주문을 처리할 수 없다
        assertThat(storeAction(101, orderId, "PREPARING").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(storeAction(103, orderId, "PREPARING").statusCode).isEqualTo(HttpStatus.OK)

        // 매장 변경은 주문당 한 번 (제조가 시작된 뒤라 여기서는 not-allowed 가 먼저 걸린다)
        assertThat(requestTransfer(31, orderId, 102, kst(date, "08:50")).statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `새 매장이 거절하면 잡아 둔 새 자리만 풀리고 원래 주문은 그대로다`() {
        val date = "2026-11-02"
        clock.set(kst(date, "08:00"))
        val orderId = paidOrder(32, kst(date, "08:40"), 1004L to 1)
        val transferId = requestTransfer(32, orderId, 103, kst(date, "08:40")).body!!["transferId"].toString()
        assertThat(reserved(103, date)).isEqualTo(2)

        val refused = refuse(103, transferId)
        assertThat(refused.body!!["status"]).isEqualTo("REFUSED")
        assertThat(refused.body!!["failReason"]).isEqualTo("REFUSED_BY_STORE")
        assertThat(reserved(103, date)).isZero()
        assertThat(reserved(101, date)).isEqualTo(2)
        assertThat(order(32, orderId)["storeId"]).isEqualTo(101)
        assertThat(outboxTypes(orderId)).contains("OrderTransferFailed")
        // 거절 뒤 수락은 받아들이지 않는다
        assertThat(accept(103, transferId).statusCode).isEqualTo(HttpStatus.CONFLICT)
        // 거절된 뒤에는 다른 매장으로 다시 요청할 수 있다
        assertThat(requestTransfer(32, orderId, 103, kst(date, "08:45")).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `새 매장이 기한 안에 답하지 않으면 만료되고 원래 주문이 유지된다`() {
        val date = "2026-11-03"
        clock.set(kst(date, "08:00"))
        val orderId = paidOrder(33, kst(date, "08:40"), 1004L to 1)
        val transferId = requestTransfer(33, orderId, 103, kst(date, "08:40")).body!!["transferId"].toString()

        clock.set(kst(date, "08:01"))
        transfers.expireOverdue()
        assertThat(transfer(33, orderId, transferId)["status"]).isEqualTo("AWAITING_STORE")
        clock.set(kst(date, "08:03")) // 기본 수락 기한 2분
        transfers.expireOverdue()

        val t = transfer(33, orderId, transferId)
        assertThat(t["status"]).isEqualTo("EXPIRED")
        assertThat(t["failReason"]).isEqualTo("STORE_DID_NOT_RESPOND")
        assertThat(reserved(103, date)).isZero()
        assertThat(order(33, orderId)["storeId"]).isEqualTo(101)
        // 기한이 지난 뒤 들어온 수락은 409
        assertThat(accept(103, transferId).statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `원래 매장이 먼저 만들기 시작하면 변경 요청은 중단되고 새 자리가 풀린다`() {
        val date = "2026-11-04"
        clock.set(kst(date, "08:00"))
        val orderId = paidOrder(34, kst(date, "08:40"), 1004L to 1)
        val transferId = requestTransfer(34, orderId, 103, kst(date, "08:40")).body!!["transferId"].toString()

        assertThat(storeAction(101, orderId, "PREPARING").statusCode).isEqualTo(HttpStatus.OK)
        val t = transfer(34, orderId, transferId)
        assertThat(t["status"]).isEqualTo("ABORTED")
        assertThat(t["failReason"]).isEqualTo("ORIGINAL_STORE_STARTED")
        assertThat(reserved(103, date)).isZero()
        assertThat(accept(103, transferId).statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(order(34, orderId)["storeId"]).isEqualTo(101)
    }

    @Test
    fun `새 매장 수락과 원래 매장 제조 시작이 동시에 와도 한쪽만 이기고 슬롯 용량은 새지 않는다`() {
        val date = "2026-11-05"
        clock.set(kst(date, "07:00"))
        val pool = Executors.newFixedThreadPool(2)
        var moved = 0
        var stayed = 0
        repeat(8) { i ->
            val pickup = kst(date, "08:00").plus(Duration.ofMinutes(10L * i))
            val orderId = paidOrder(3500L + i, pickup, 1004L to 1)
            val transferId = requestTransfer(3500L + i, orderId, 103, pickup).body!!["transferId"].toString()

            val start = CountDownLatch(1)
            val a = pool.submit<Int> {
                start.await()
                accept(103, transferId).statusCode.value()
            }
            val b = pool.submit<Int> {
                start.await()
                storeAction(101, orderId, "PREPARING").statusCode.value()
            }
            start.countDown()
            val (acceptCode, prepareCode) = a.get(30, TimeUnit.SECONDS) to b.get(30, TimeUnit.SECONDS)

            val o = order(3500L + i, orderId)
            if (o["storeId"] == 103) {
                moved++
                assertThat(acceptCode).isEqualTo(200)
                assertThat(prepareCode).isEqualTo(404) // 이미 새 매장 주문
                assertThat(o["status"]).isEqualTo("PAID")
            } else {
                stayed++
                assertThat(prepareCode).isEqualTo(200)
                assertThat(acceptCode).isEqualTo(409)
                assertThat(o["status"]).isEqualTo("PREPARING")
            }
        }
        pool.shutdown()
        // 어느 쪽이 이겼든 8잔(부하 2)이 두 매장 합쳐 정확히 한 번씩만 잡혀 있다
        assertThat(reserved(101, date) + reserved(103, date)).isEqualTo(16)
        assertThat(moved + stayed).isEqualTo(8)
    }

    @Test
    fun `같은 키 재요청은 같은 변경 요청, 진행 중에 다른 요청은 409, 같은 키에 다른 내용은 422`() {
        val date = "2026-11-06"
        clock.set(kst(date, "08:00"))
        val orderId = paidOrder(36, kst(date, "08:40"), 1004L to 1)

        val first = requestTransfer(36, orderId, 103, kst(date, "08:40"), key = "transfer-key-36")
        val retry = requestTransfer(36, orderId, 103, kst(date, "08:40"), key = "transfer-key-36")
        assertThat(retry.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(retry.headers.getFirst("Idempotent-Replayed")).isEqualTo("true")
        assertThat(retry.body!!["transferId"]).isEqualTo(first.body!!["transferId"])
        assertThat(reserved(103, date)).isEqualTo(2)

        val other = requestTransfer(36, orderId, 102, kst(date, "08:40"))
        assertThat(other.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(other.body!!["type"].toString()).endsWith("transfer-in-progress")

        val reused = requestTransfer(36, orderId, 103, kst(date, "08:50"), key = "transfer-key-36")
        assertThat(reused.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `같은 음료를 팔지 않거나 새 매장에 자리가 없으면 요청 단계에서 거절하고 아무것도 잡지 않는다`() {
        val date = "2026-11-07"
        clock.set(kst(date, "08:00"))
        val smoothie = paidOrder(37, kst(date, "08:40"), 1005L to 1) // 딸기 스무디: 선릉점에는 없음
        val incompatible = requestTransfer(37, smoothie, 102, kst(date, "08:40"))
        assertThat(incompatible.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(incompatible.body!!["reason"]).isEqualTo("NOT_SOLD")
        assertThat(incompatible.body!!["notSold"] as List<*>).containsExactly("딸기 스무디")

        // 선릉점 에스프레소 용량 4/슬롯, 아이스 바닐라라떼(부하 2, 신선도 10분=2슬롯) 5잔 = 10 > 8 → 어느 시각도 불가
        val big = paidOrder(38, kst(date, "09:00"), 1004L to 5)
        val full = requestTransfer(38, big, 102, kst(date, "09:00"))
        assertThat(full.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(full.body!!["type"].toString()).endsWith("pickup-slot-unavailable")
        assertThat(reserved(102, date)).isZero()

        val options = rest.exchange("/orders/$smoothie/transfer-options", HttpMethod.GET, entity(null, 37), Map::class.java).body!!
        assertThat(options["transferable"]).isEqualTo(true)
        val byStore = (options["candidates"] as List<*>).associateBy { (it as Map<*, *>)["storeId"] } as Map<*, Map<*, *>>
        assertThat(byStore[102]!!["compatible"]).isEqualTo(false)
        assertThat(byStore[103]!!["compatible"]).isEqualTo(true)
        assertThat(byStore[103]!!["sameTimeAvailable"]).isEqualTo(true)
    }

    @Test
    fun `주문할 때 원하는 시각이 꽉 차면 그 시각 그대로 되는 같은 브랜드 매장을 함께 알려준다`() {
        val date = "2026-11-08"
        clock.set(kst(date, "08:00"))
        val pickup = kst(date, "08:30")
        assertThat(place(39, pickup, 1003L to 3).statusCode).isEqualTo(HttpStatus.CREATED) // 핫 카페라떼 3잔 = 에스프레소 6 (가득)

        val rejected = place(40, pickup, 1003L to 1)
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val nearby = (rejected.body!!["nearbyStores"] as List<*>).map { it as Map<*, *> }
        assertThat(nearby.map { it["storeId"] }).containsExactly(104, 103, 102) // 걸어서 가까운 순
        val samseong = nearby.single { it["storeId"] == 103 }
        assertThat(samseong["pickupAt"]).isEqualTo(pickup.toString())
        assertThat(samseong["items"]).isEqualTo(listOf(mapOf("menuItemId" to 4003, "quantity" to 1)))
        // 제안받은 그대로 주문하면 된다
        assertThat(place(40, pickup, 4003L to 1, storeId = 103).statusCode).isEqualTo(HttpStatus.CREATED)
    }
}
