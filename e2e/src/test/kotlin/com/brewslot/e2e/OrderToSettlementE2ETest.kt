package com.brewslot.e2e

import com.brewslot.common.BusinessTime
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 주문 → 포인트 차감 → 카드 결제 → 제조/픽업 → 적립 → 정산 → PG 대사 까지,
 * 4개 서비스가 실제 Kafka 로만 대화하는 전체 흐름을 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OrderToSettlementE2ETest {
    private lateinit var order: RestClient
    private lateinit var loyalty: RestClient
    private lateinit var payment: RestClient
    private lateinit var settlement: RestClient

    private val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}
    private val listType = object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}
    private val slow = Duration.ofSeconds(30)

    @BeforeAll
    fun boot() {
        Platform.start()
        order = Platform.client(Platform.order)
        loyalty = Platform.client(Platform.loyalty)
        payment = Platform.client(Platform.payment)
        settlement = Platform.client(Platform.settlement)
    }

    private fun pickupSoon(minutes: Long): Instant {
        val t = Instant.now().plus(Duration.ofMinutes(minutes))
        return Instant.ofEpochSecond((t.epochSecond / 300 + 1) * 300)
    }

    private fun grant(memberId: Long, amount: Long) = loyalty.post().uri("/admin/point-grants").contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("memberId" to memberId, "brandId" to 1, "amount" to amount)).retrieve().toBodilessEntity()

    private fun balance(memberId: Long): Long =
        loyalty.get().uri("/members/{m}/wallets", memberId).retrieve().body(listType)!!.firstOrNull { it["brandId"] == 1 }?.get("balance")
            ?.toString()?.toLong() ?: 0

    private fun place(memberId: Long, pickupAt: Instant, points: Long, vararg items: Pair<Long, Int>): String =
        order.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
            .header("X-Member-Id", memberId.toString()).header("Idempotency-Key", "e2e-" + UUID.randomUUID())
            .body(
                mapOf(
                    "storeId" to 101,
                    "pickupAt" to pickupAt.toString(),
                    "pointsToUse" to points,
                    "items" to items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) },
                ),
            )
            .retrieve().body(mapType)!!["orderId"].toString()

    private fun pay(memberId: Long, orderId: String, token: String) = order.post().uri("/orders/{id}/payment", orderId)
        .contentType(MediaType.APPLICATION_JSON).header("X-Member-Id", memberId.toString())
        .body(mapOf("cardToken" to token)).retrieve().toBodilessEntity()

    private fun orderOf(memberId: Long, orderId: String) =
        order.get().uri("/orders/{id}", orderId).header("X-Member-Id", memberId.toString()).retrieve().body(mapType)!!

    private fun storeStatus(orderId: String, status: String) = order.put().uri("/stores/101/orders/{id}/status", orderId)
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("status" to status)).retrieve().body(mapType)!!

    private val happyMember = 700_000L + (System.nanoTime() % 10_000)
    private lateinit var happyOrderId: String

    @Test
    @Order(1)
    fun `정상 흐름 - 포인트+카드 복합결제 후 픽업하면 카드 결제액 기준으로 적립된다`() {
        grant(happyMember, 3_000)
        happyOrderId = place(happyMember, pickupSoon(20), 2_000, 1002L to 2, 1005L to 1) // 3,500×2 + 6,000 = 13,000
        pay(happyMember, happyOrderId, "tok_visa")

        await withPollInterval Duration.ofMillis(200) atMost slow untilAsserted {
            assertThat(orderOf(happyMember, happyOrderId)["status"]).isEqualTo("PAID")
        }
        assertThat(balance(happyMember)).isEqualTo(1_000)
        val pay = payment.get().uri("/payments/{id}", happyOrderId).retrieve().body(mapType)!!
        assertThat(pay["amount"]).isEqualTo(11_000)
        assertThat(pay["status"]).isEqualTo("CAPTURED")

        storeStatus(happyOrderId, "PREPARING")
        storeStatus(happyOrderId, "READY")
        storeStatus(happyOrderId, "PICKED_UP")

        await atMost slow untilAsserted { assertThat(balance(happyMember)).isEqualTo(1_000 + 330) } // 11,000 × 3%
    }

    @Test
    @Order(2)
    fun `카드 거절 - 이미 차감된 포인트가 자동으로 복원된다`() {
        val member = happyMember + 1
        grant(member, 5_000)
        val orderId = place(member, pickupSoon(25), 1_500, 1001L to 1, 1006L to 1)
        pay(member, orderId, "tok_declined")

        await atMost slow untilAsserted {
            val o = orderOf(member, orderId)
            assertThat(o["status"]).isEqualTo("CANCELLED")
            assertThat(o["cancelReason"]).isEqualTo("PAYMENT_DECLINED")
            assertThat(balance(member)).isEqualTo(5_000)
        }
    }

    @Test
    @Order(3)
    fun `PG 타임아웃 - 주문은 타임아웃으로 취소되고, 뒤늦게 확인된 승인은 자동 취소되며 포인트도 복원된다`() {
        val member = happyMember + 2
        grant(member, 2_000)
        val orderId = place(member, pickupSoon(30), 1_000, 1003L to 1)
        pay(member, orderId, "tok_timeout")

        await atMost slow untilAsserted {
            assertThat(orderOf(member, orderId)["cancelReason"]).isEqualTo("PAYMENT_TIMEOUT")
            assertThat(payment.get().uri("/payments/{id}", orderId).retrieve().body(mapType)!!["status"]).isEqualTo("REFUNDED")
            assertThat(balance(member)).isEqualTo(2_000)
        }
    }

    @Test
    @Order(4)
    fun `정산과 대사 - 오늘 거래가 매장 정산서에 반영되고 PG 파일과 불일치가 없다`() {
        val today = BusinessTime.businessDateOf(Instant.now())

        await atMost slow untilAsserted {
            val recon = settlement.post().uri("/reconciliation-runs").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("businessDate" to today.toString())).retrieve().body(mapType)!!
            @Suppress("UNCHECKED_CAST")
            assertThat(recon["issues"] as List<Any>).isEmpty()
            assertThat(recon["matchedCount"] as Int).isGreaterThanOrEqualTo(3) // 정상 승인 + 타임아웃 건의 승인·취소
        }

        val statements = settlement.post().uri("/settlement-runs").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("businessDate" to today.toString())).retrieve().body(listType)!!

        @Suppress("UNCHECKED_CAST")
        val draft = statements.map { it["draft"] as Map<String, Any?> }.single { it["storeId"] == 101 }
        assertThat((draft["cardSales"] as Number).toLong()).isGreaterThanOrEqualTo(11_000)
        assertThat((draft["pointSales"] as Number).toLong()).isGreaterThanOrEqualTo(2_000)
        val net = (draft["netSales"] as Number).toLong()
        val payout = (draft["payout"] as Number).toLong()
        assertThat(payout).isEqualTo(net - (draft["pgFee"] as Number).toLong() - (draft["platformFee"] as Number).toLong())
    }

    @Test
    @Order(5)
    fun `재주문 추천 - 픽업 완료한 장바구니가 현재 가격과 가장 빠른 픽업 시각과 함께 추천된다`() {
        await atMost slow untilAsserted {
            val suggestions = order.get().uri("/members/{m}/reorder-suggestions", happyMember)
                .header("X-Member-Id", happyMember.toString()).retrieve().body(listType)!!
            assertThat(suggestions).hasSize(1)
            assertThat(suggestions[0]["sourceOrderId"]).isEqualTo(happyOrderId)
            assertThat(suggestions[0]["orderable"]).isEqualTo(true)
            assertThat(suggestions[0]["earliestPickupAt"]).isNotNull()
        }
    }
}
