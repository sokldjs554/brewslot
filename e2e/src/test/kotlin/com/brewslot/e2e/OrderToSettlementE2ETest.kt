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
 * 주문 → 쿠폰·포인트 사용 → 카드 결제 → 제조/픽업 → 적립 → 정산 → PG 대사 까지,
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
        placeWithCoupon(memberId, pickupAt, points, null, 0, *items)

    private fun placeWithCoupon(
        memberId: Long,
        pickupAt: Instant,
        points: Long,
        couponId: String?,
        couponAmount: Long,
        vararg items: Pair<Long, Int>,
    ): String = order.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
        .header("X-Member-Id", memberId.toString()).header("Idempotency-Key", "e2e-" + UUID.randomUUID())
        .body(
            mapOf(
                "storeId" to 101,
                "pickupAt" to pickupAt.toString(),
                "pointsToUse" to points,
                "couponId" to couponId,
                "couponAmount" to couponAmount,
                "items" to items.map { mapOf("menuItemId" to it.first, "quantity" to it.second) },
            ),
        )
        .retrieve().body(mapType)!!["orderId"].toString()

    private val campaignId: Long by lazy {
        loyalty.post().uri("/admin/promotions").contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "brandId" to 1,
                    "name" to "E2E 오픈 기념 1,000원",
                    "discountAmount" to 1_000,
                    "minOrderAmount" to 5_000,
                    "issueLimit" to 10,
                    "endsAt" to Instant.now().plus(Duration.ofDays(7)).toString(),
                ),
            )
            .retrieve().body(mapType)!!["campaignId"].toString().toLong()
    }

    private fun claimCoupon(memberId: Long): String = loyalty.post().uri("/promotions/{c}/claims", campaignId)
        .header("X-Member-Id", memberId.toString()).retrieve().body(mapType)!!["couponId"].toString()

    private fun couponStatus(memberId: Long, couponId: String): String =
        loyalty.get().uri("/members/{m}/coupons", memberId).retrieve().body(listType)!!
            .single { it["couponId"] == couponId }["status"].toString()

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
    fun `쿠폰 이벤트 - 선착순 쿠폰 + 포인트 + 카드로 결제하면 카드에는 할인 후 금액만 청구되고 쿠폰은 사용 처리된다`() {
        val member = happyMember + 3
        grant(member, 1_000)
        val couponId = claimCoupon(member)
        assertThat(claimCoupon(member)).isEqualTo(couponId) // 재요청은 같은 쿠폰 (회원당 1장)

        val orderId = placeWithCoupon(member, pickupSoon(35), 500, couponId, 1_000, 1004L to 1, 1002L to 1) // 5,000 + 3,500 = 8,500
        pay(member, orderId, "tok_visa")

        await atMost slow untilAsserted {
            assertThat(orderOf(member, orderId)["status"]).isEqualTo("PAID")
            assertThat(payment.get().uri("/payments/{id}", orderId).retrieve().body(mapType)!!["amount"]).isEqualTo(7_000)
            assertThat(couponStatus(member, couponId)).isEqualTo("USED")
            assertThat(balance(member)).isEqualTo(500)
        }
    }

    @Test
    @Order(5)
    fun `쿠폰 보상 - 카드가 거절되면 쿠폰과 포인트가 함께 되돌아와 다시 쓸 수 있다`() {
        val member = happyMember + 4
        grant(member, 1_000)
        val couponId = claimCoupon(member)
        val orderId = placeWithCoupon(member, pickupSoon(40), 1_000, couponId, 1_000, 1005L to 1) // 6,000
        pay(member, orderId, "tok_declined")

        await atMost slow untilAsserted {
            assertThat(orderOf(member, orderId)["cancelReason"]).isEqualTo("PAYMENT_DECLINED")
            assertThat(couponStatus(member, couponId)).isEqualTo("ISSUED")
            assertThat(balance(member)).isEqualTo(1_000)
        }

        // 할인액을 부풀린 요청은 loyalty-service 가 거절하고, 주문은 쿠폰 거절로 취소된다(포인트도 그대로).
        val tampered = placeWithCoupon(member, pickupSoon(45), 0, couponId, 3_000, 1005L to 1)
        pay(member, tampered, "tok_visa")
        await atMost slow untilAsserted {
            assertThat(orderOf(member, tampered)["cancelReason"]).isEqualTo("COUPON_REJECTED")
            assertThat(couponStatus(member, couponId)).isEqualTo("ISSUED")
        }
    }

    @Test
    @Order(6)
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
        assertThat((draft["couponSales"] as Number).toLong()).isGreaterThanOrEqualTo(1_000) // 브랜드 부담 할인분도 매장 매출
        assertThat((draft["couponReversals"] as Number).toLong()).isLessThanOrEqualTo(0)
        val net = (draft["netSales"] as Number).toLong()
        val payout = (draft["payout"] as Number).toLong()
        assertThat(payout).isEqualTo(net - (draft["pgFee"] as Number).toLong() - (draft["platformFee"] as Number).toLong())
    }

    @Test
    @Order(7)
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
