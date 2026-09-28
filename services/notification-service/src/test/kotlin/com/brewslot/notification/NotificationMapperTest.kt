package com.brewslot.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("주문 이벤트 → 고객 알림 변환")
class NotificationMapperTest {
    private val json = ObjectMapper()
    private val at = Instant.parse("2026-09-28T00:00:00Z")

    private fun payload(vararg kv: Pair<String, Any>) = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf(*kv))

    @Test
    fun `준비 완료는 약속보다 몇 분 먼저인지 알려 준다`() {
        val n = NotificationMapper.from(
            "e1",
            "OrderReady",
            at,
            payload("orderId" to "o1", "memberId" to 7, "promisedPickupAt" to "2026-09-28T00:45:00Z", "readyAt" to "2026-09-28T00:42:00Z"),
        )!!
        assertThat(n.memberId).isEqualTo(7)
        assertThat(n.title).isEqualTo("음료가 준비됐어요")
        assertThat(n.body).contains("3분 먼저")
        assertThat(n.id).isEqualTo("${at.toEpochMilli()}:e1")
    }

    @Test
    fun `취소 사유마다 고객이 알아야 할 것을 다르게 말한다`() {
        val declined = NotificationMapper.from(
            "e", "OrderCancelled", at,
            payload(
                "orderId" to "o", "memberId" to 1, "reason" to "PAYMENT_DECLINED",
                "wasPaid" to false,
            ),
        )!!
        val store = NotificationMapper.from(
            "e", "OrderCancelled", at,
            payload(
                "orderId" to "o", "memberId" to 1, "reason" to "REJECTED_BY_STORE",
                "wasPaid" to true,
            ),
        )!!
        assertThat(declined.body).contains("쿠폰·포인트는 돌려드렸어요")
        assertThat(store.body).contains("전액 취소")
    }

    @Test
    fun `알림이 필요 없는 이벤트와 회원을 모르는 이벤트는 무시한다`() {
        assertThat(NotificationMapper.from("e", "OrderPlaced", at, payload("orderId" to "o", "memberId" to 1))).isNull()
        assertThat(NotificationMapper.from("e", "OrderPreparing", at, payload("orderId" to "o"))).isNull() // 구버전 이벤트(memberId 없음)
    }

    @Test
    fun `재연결 커서 - 커서 이후의 알림만 이어 보낸다`() {
        val n = Notification("${at.toEpochMilli()}:e2", 1, "o", "OrderReady", "t", "b", at)
        assertThat(n.isAfter(null)).isTrue()
        assertThat(n.isAfter("${at.toEpochMilli() - 1}:e1")).isTrue()
        assertThat(n.isAfter("${at.toEpochMilli()}:e2")).isFalse() // 자기 자신
        assertThat(n.isAfter("${at.toEpochMilli() + 1}:e3")).isFalse()
    }
}
