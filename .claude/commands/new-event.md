---
description: 새 도메인 이벤트/명령을 끝까지(계약→발행→소비→테스트) 추가한다
argument-hint: <EventName> <발행 서비스> <구독 서비스들>
---

$ARGUMENTS 에 대해:

1. `libs/messaging/.../contract/` 에 data class 추가. 구독자가 발행자를 다시 조회하지 않도록 필요한 사실을 담는다(Event-Carried State Transfer).
   식별자는 String/Long, 금액은 원 단위 Long, 시각은 Instant.
2. 발행: 상태 변경과 **같은 트랜잭션**에서 `outbox.publish(topic, aggregateId, payload)`. key 는 순서가 필요한 단위(보통 orderId).
3. 소비: `@KafkaListener` 에서 `eventType` 으로 분기, `inbox.process(CONSUMER, envelope) { ... }` 또는 자연 키 유니크 제약.
   모르는 eventType 은 무시한다(다른 이벤트와 토픽을 공유하므로).
4. 테스트: 발행 측은 outbox 행 검증, 소비 측은 **같은 이벤트 2번 전달 → 1번 반영** 을 반드시 포함.
5. 흐름에 영향이 있으면 E2E(`e2e/`) 시나리오를 추가하고 ADR 의 실패 시나리오 표를 갱신한다.
