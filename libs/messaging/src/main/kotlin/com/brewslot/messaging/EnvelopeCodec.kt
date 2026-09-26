package com.brewslot.messaging

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue

/**
 * 봉투 직렬화/역직렬화.
 *
 * 계약 호환성 규칙: 소비자는 모르는 필드를 무시한다(FAIL_ON_UNKNOWN_PROPERTIES=false).
 * 생산자는 필드를 "추가"만 할 수 있고, 삭제/의미 변경은 새 eventType 으로 발행한다.
 */
class EnvelopeCodec(objectMapper: ObjectMapper) {
    val mapper: ObjectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun encode(envelope: EventEnvelope): String = mapper.writeValueAsString(envelope)

    fun decode(json: String): EventEnvelope = mapper.readValue(json)

    fun <T : Any> payloadOf(envelope: EventEnvelope, type: Class<T>): T = mapper.treeToValue(envelope.payload, type)

    inline fun <reified T : Any> payloadOf(envelope: EventEnvelope): T = mapper.treeToValue<T>(envelope.payload)
}
