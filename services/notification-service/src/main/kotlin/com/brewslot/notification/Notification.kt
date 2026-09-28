package com.brewslot.notification

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

/** 고객 앱에 보내는 알림 한 건. SSE 의 id 는 재연결(Last-Event-ID) 때 이어 받기 위한 커서다. */
data class Notification(
    val id: String,
    val memberId: Long,
    val orderId: String,
    val type: String,
    val title: String,
    val body: String,
    val occurredAt: Instant,
) {
    /** Last-Event-ID 커서보다 뒤의 알림인가. 커서 = "<발생 시각 ms>:<이벤트 ID>" (인스턴스가 달라도 비교 가능) */
    fun isAfter(cursor: String?): Boolean {
        if (cursor.isNullOrBlank()) return true
        val millis = cursor.substringBefore(':').toLongOrNull() ?: return true
        val mine = occurredAt.toEpochMilli()
        return mine > millis || (mine == millis && id != cursor)
    }
}

/**
 * 주문 도메인 이벤트(order.events) → 고객이 알아야 할 알림. 알림이 필요 없는 이벤트는 null.
 * 문구는 "고객이 무엇을 하면 되는지" 기준으로 쓴다.
 */
object NotificationMapper {
    fun from(eventId: String, eventType: String, occurredAt: Instant, p: JsonNode): Notification? {
        val memberId = p.path("memberId").asLong(0)
        if (memberId == 0L) return null
        val orderId = p.path("orderId").asText()
        val pickup = p.path("promisedPickupAt").takeIf { it.isTextual }?.asText()?.let(Instant::parse)
        val (title, body) = when (eventType) {
            "OrderPaid" -> "주문이 확정됐어요" to "약속한 픽업 시각에 맞춰 만들어 드릴게요."
            "OrderPreparing" -> "음료를 만들기 시작했어요" to "곧 준비돼요."
            "OrderReady" -> "음료가 준비됐어요" to readyBody(pickup, p.path("readyAt").asText(null)?.let(Instant::parse))
            "OrderPickedUp" -> "맛있게 드세요" to "픽업이 완료됐어요. 포인트는 곧 적립돼요."
            "OrderCancelled" -> "주문이 취소됐어요" to cancelBody(p.path("reason").asText(), p.path("wasPaid").asBoolean())
            "OrderTransferred" ->
                "픽업 매장이 바뀌었어요" to
                    "결제 · 쿠폰 · 포인트는 그대로예요. 새 매장에서 ${pickup?.let(::kst) ?: "약속한 시각"}에 받아가세요."
            "OrderTransferFailed" -> "매장 변경이 이뤄지지 않았어요" to transferFailedBody(p.path("reason").asText())
            else -> return null
        }
        return Notification("${occurredAt.toEpochMilli()}:$eventId", memberId, orderId, eventType, title, body, occurredAt)
    }

    private fun readyBody(pickup: Instant?, readyAt: Instant?): String {
        if (pickup == null || readyAt == null) return "픽업대에서 받아가세요."
        val early = Duration.between(readyAt, pickup).toMinutes()
        return if (early >= 0) "약속한 시각보다 ${early}분 먼저 준비됐어요. 픽업대에서 받아가세요." else "약속보다 늦어져 죄송해요. 픽업대에서 받아가세요."
    }

    private fun transferFailedBody(reason: String): String = when (reason) {
        "REFUSED_BY_STORE" -> "새 매장이 주문을 받지 못했어요. 원래 매장 주문은 그대로 진행돼요."
        "STORE_DID_NOT_RESPOND" -> "새 매장의 응답이 없어 변경을 취소했어요. 원래 매장 주문은 그대로 진행돼요."
        "ORIGINAL_STORE_STARTED" -> "원래 매장에서 이미 음료를 만들기 시작했어요. 원래 매장에서 받아가세요."
        else -> "원래 주문 상태를 확인해 주세요."
    }

    private fun kst(at: Instant): String = HH_MM.format(at.atZone(KST))

    private val KST = java.time.ZoneId.of("Asia/Seoul")
    private val HH_MM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    private fun cancelBody(reason: String, wasPaid: Boolean): String = when (reason) {
        "PAYMENT_DECLINED" -> "카드 승인이 거절됐어요. 사용한 쿠폰·포인트는 돌려드렸어요."
        "PAYMENT_TIMEOUT" -> "결제 결과를 확인하지 못해 주문을 취소했어요. 승인됐다면 자동으로 취소돼요."
        "COUPON_REJECTED" -> "쿠폰을 사용할 수 없어 주문을 취소했어요."
        "REJECTED_BY_STORE" -> "매장 사정으로 주문을 받지 못했어요. 결제는 전액 취소돼요."
        else -> if (wasPaid) "결제는 전액 취소되고 쿠폰·포인트는 돌려드려요." else "다시 주문해 주세요."
    }
}
