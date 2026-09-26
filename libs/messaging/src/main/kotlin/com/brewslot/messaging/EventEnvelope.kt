package com.brewslot.messaging

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Kafka 로 오가는 모든 메시지의 공통 봉투.
 *
 * @property eventId 전역 유일 ID. 소비자는 (consumer, eventId) 로 중복 처리를 막는다(Inbox).
 * @property eventType payload 의 타입 이름(계약 클래스의 simpleName). 소비자는 이 값으로 분기한다.
 * @property aggregateId Kafka key 로도 쓰이는 집합체 ID.
 * @property correlationId Saga/요청 단위를 묶는 ID (주문 흐름에서는 orderId).
 * @property causationId 이 메시지를 발생시킨 직전 메시지의 eventId.
 */
data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val aggregateId: String,
    val occurredAt: Instant,
    val correlationId: String?,
    val causationId: UUID?,
    val payload: JsonNode,
)
