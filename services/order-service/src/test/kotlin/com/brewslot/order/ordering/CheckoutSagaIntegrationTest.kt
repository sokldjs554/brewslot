package com.brewslot.order.ordering

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.messaging.contract.PaymentFailed
import com.brewslot.messaging.contract.PointsRedeemed
import com.brewslot.messaging.contract.PointsRedemptionFailed
import com.brewslot.order.OrderIntegrationTest
import com.brewslot.order.ordering.application.ExpiryJobs
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

class CheckoutSagaIntegrationTest : OrderIntegrationTest() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    @Autowired lateinit var codec: EnvelopeCodec

    @Autowired lateinit var expiryJobs: ExpiryJobs

    private fun kst(date: String, time: String) = Instant.parse("${date}T$time:00+09:00")

    private fun place(
        memberId: Long,
        pickupAt: Instant,
        points: Long = 0,
        vararg items: Pair<Long, Int> = arrayOf(
            1002L to 1,
            1005L to 1,
        ),
    ): String {
        val body = mapOf(
            "storeId" to 101,
            "pickupAt" to pickupAt.toString(),
            "items" to items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) },
            "pointsToUse" to points,
        )
        val res = rest.postForEntity("/orders", entity(body, memberId, "s-" + UUID.randomUUID()), Map::class.java)
        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        return res.body!!["orderId"].toString()
    }

    private fun pay(memberId: Long, orderId: String, token: String = "tok_visa") =
        rest.postForEntity("/orders/$orderId/payment", entity(mapOf("cardToken" to token), memberId), Map::class.java)

    private fun order(memberId: Long, orderId: String) =
        rest.exchange("/orders/$orderId", HttpMethod.GET, entity(null, memberId), Map::class.java).body!!

    private fun commands(orderId: String): List<String> = jdbc.sql(
        "SELECT event_type FROM outbox_event WHERE message_key = :k AND topic IN ('payment.commands', 'loyalty.commands') ORDER BY id",
    ).param("k", orderId).query(String::class.java).list()

    private fun reply(topic: String, orderId: String, payload: Any, eventId: UUID = UUID.randomUUID()): UUID {
        val envelope =
            EventEnvelope(
                eventId,
                payload::class.java.simpleName,
                orderId,
                clock.instant(),
                orderId,
                null,
                codec.mapper.valueToTree(payload),
            )
        kafka.send(topic, orderId, codec.encode(envelope)).get()
        return eventId
    }

    private fun reservationStatus(orderId: String) = jdbc.sql("SELECT status FROM slot_reservation WHERE order_id = CAST(:id AS uuid)")
        .param("id", orderId).query(String::class.java).single()

    private fun changeStatus(orderId: String, status: String) = rest.exchange(
        "/stores/101/orders/$orderId/status",
        HttpMethod.PUT,
        entity(mapOf("status" to status), 0),
        Map::class.java,
    )

    @Test
    fun `카드 결제 성공 → 주문 확정 → 매장 처리 → 픽업, 조회 모델과 대시보드까지 반영된다`() {
        clock.set(kst("2026-10-10", "08:00"))
        val pickup = kst("2026-10-10", "08:30")
        val orderId = place(11, pickup)

        val accepted = pay(11, orderId)
        assertThat(accepted.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(accepted.body!!["status"]).isEqualTo("PAYMENT_IN_PROGRESS")
        assertThat(commands(orderId)).containsExactly("ChargePayment")

        // 결제 요청 재전송은 멱등
        assertThat(pay(11, orderId).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(commands(orderId)).containsExactly("ChargePayment")

        val captured = PaymentCaptured("pay-1", orderId, 11, 101, 1, 9500, "pg-1", clock.instant())
        val eventId = reply(Topics.PAYMENT_EVENTS, orderId, captured)
        reply(Topics.PAYMENT_EVENTS, orderId, captured, eventId) // 같은 이벤트 중복 전달

        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(order(11, orderId)["status"]).isEqualTo("PAID") }
        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED")

        clock.set(kst("2026-10-10", "08:20"))
        assertThat(changeStatus(orderId, "PREPARING").statusCode).isEqualTo(HttpStatus.OK)
        clock.set(kst("2026-10-10", "08:28"))
        assertThat(changeStatus(orderId, "READY").statusCode).isEqualTo(HttpStatus.OK)
        clock.set(kst("2026-10-10", "08:31"))
        assertThat(changeStatus(orderId, "PICKED_UP").body!!["status"]).isEqualTo("PICKED_UP")
        assertThat(changeStatus(orderId, "PREPARING").statusCode).isEqualTo(HttpStatus.CONFLICT)

        await atMost Duration.ofSeconds(15) untilAsserted {
            val page = rest.exchange("/members/11/orders", HttpMethod.GET, entity(null, 11), Map::class.java).body!!

            @Suppress("UNCHECKED_CAST")
            val first = (page["items"] as List<Map<String, Any>>).first()
            assertThat(first["orderId"]).isEqualTo(orderId)
            assertThat(first["status"]).isEqualTo("PICKED_UP")

            val dashboard = rest.getForEntity("/stores/101/dashboard?date=2026-10-10", Map::class.java).body!!
            assertThat(dashboard["paidOrders"]).isEqualTo(1)
            assertThat(dashboard["promiseKeptRate"]).isEqualTo(1.0)
        }
    }

    @Test
    fun `포인트가 부족하면 카드 결제를 시도하지 않고 취소하며 슬롯을 돌려준다`() {
        clock.set(kst("2026-10-11", "08:00"))
        val orderId = place(12, kst("2026-10-11", "08:30"), points = 3000)
        pay(12, orderId)
        assertThat(commands(orderId)).containsExactly("RedeemPoints")

        reply(Topics.LOYALTY_EVENTS, orderId, PointsRedemptionFailed(orderId, "INSUFFICIENT_BALANCE", 1200, clock.instant()))

        await atMost Duration.ofSeconds(15) untilAsserted {
            val o = order(12, orderId)
            assertThat(o["status"]).isEqualTo("CANCELLED")
            assertThat(o["cancelReason"]).isEqualTo("POINTS_INSUFFICIENT")
        }
        assertThat(commands(orderId)).containsExactly("RedeemPoints")
        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED")
    }

    @Test
    fun `카드 결제가 거절되면 이미 차감한 포인트를 되돌리는 보상 명령을 보낸다`() {
        clock.set(kst("2026-10-12", "08:00"))
        val orderId = place(13, kst("2026-10-12", "08:30"), points = 3000)
        pay(13, orderId)
        reply(Topics.LOYALTY_EVENTS, orderId, PointsRedeemed(orderId, 13, 1, 101, 3000, clock.instant()))
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(commands(orderId)).containsExactly("RedeemPoints", "ChargePayment") }

        reply(Topics.PAYMENT_EVENTS, orderId, PaymentFailed(orderId, "CARD_DECLINED", clock.instant()))

        await atMost Duration.ofSeconds(15) untilAsserted {
            assertThat(order(13, orderId)["cancelReason"]).isEqualTo("PAYMENT_DECLINED")
            assertThat(commands(orderId)).containsExactly("RedeemPoints", "ChargePayment", "ReverseRedemption")
        }
    }

    @Test
    fun `응답이 오지 않으면 타임아웃 후 가능한 모든 보상을 보내고, 늦게 온 승인은 무시한다`() {
        clock.set(kst("2026-10-13", "08:00"))
        val orderId = place(14, kst("2026-10-13", "08:30"), points = 1000)
        pay(14, orderId)
        reply(Topics.LOYALTY_EVENTS, orderId, PointsRedeemed(orderId, 14, 1, 101, 1000, clock.instant()))
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(commands(orderId)).contains("ChargePayment") }

        clock.advance(Duration.ofMinutes(3))
        expiryJobs.timeoutSagas()

        val o = order(14, orderId)
        assertThat(o["status"]).isEqualTo("CANCELLED")
        assertThat(o["cancelReason"]).isEqualTo("PAYMENT_TIMEOUT")
        assertThat(commands(orderId)).containsExactly("RedeemPoints", "ChargePayment", "RefundPayment", "ReverseRedemption")

        reply(Topics.PAYMENT_EVENTS, orderId, PaymentCaptured("pay-late", orderId, 14, 101, 1, 8500, "pg-late", clock.instant()))
        Thread.sleep(1500)
        assertThat(order(14, orderId)["status"]).isEqualTo("CANCELLED")
    }

    @Test
    fun `결제 후 매장이 거절하면 환불과 포인트 복원 명령이 함께 나간다`() {
        clock.set(kst("2026-10-14", "08:00"))
        val orderId = place(15, kst("2026-10-14", "08:30"), points = 500)
        pay(15, orderId)
        reply(Topics.LOYALTY_EVENTS, orderId, PointsRedeemed(orderId, 15, 1, 101, 500, clock.instant()))
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(commands(orderId)).contains("ChargePayment") }
        reply(Topics.PAYMENT_EVENTS, orderId, PaymentCaptured("pay-2", orderId, 15, 101, 1, 9000, "pg-2", clock.instant()))
        await atMost Duration.ofSeconds(15) untilAsserted { assertThat(order(15, orderId)["status"]).isEqualTo("PAID") }

        val rejected = changeStatus(orderId, "REJECTED")

        assertThat(rejected.body!!["status"]).isEqualTo("CANCELLED")
        assertThat(commands(orderId)).containsExactly("RedeemPoints", "ChargePayment", "RefundPayment", "ReverseRedemption")
        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED")
    }
}
