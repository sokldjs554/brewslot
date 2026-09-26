# ADR-0003. Transactional Outbox 와 단일 릴레이

- 상태: 채택
- 관련 코드: `libs/messaging/outbox/*`, `libs/messaging/inbox/Inbox.kt`

## 맥락

"주문 저장 후 Kafka 발행" 을 코드로 순서대로 호출하면 **dual write** 문제가 생긴다.
DB 커밋 후 발행 전에 죽으면 메시지 유실, 발행 후 커밋 전에 롤백되면 존재하지 않는 주문의 이벤트가 나간다.
결제·포인트·정산이 이벤트로 연결된 이 시스템에서 유실은 곧 돈의 불일치다.

## 결정

1. 서비스 코드는 `KafkaTemplate` 을 직접 쓰지 않는다. `Outbox.publish()` 만 쓰며, 이 메서드는
   **활성 트랜잭션이 없으면 예외**를 던진다(실수로 트랜잭션 밖에서 호출하는 것을 컴파일 다음으로 빠르게 잡는다).
2. `OutboxRelay` 가 200ms 마다 미발행 행을 id 순으로 읽어 전송하고, **모든 ack 를 받은 뒤** `published_at` 을 기록한다(at-least-once).
3. 릴레이는 `pg_try_advisory_xact_lock` 으로 **동시에 하나만** 동작한다.
   여러 인스턴스가 `SKIP LOCKED` 로 배치를 나눠 가지면 처리량은 늘지만, 같은 주문의 이벤트(예: ChargePayment → RefundPayment)가
   서로 다른 배치로 나뉘어 **순서가 뒤바뀔 수 있다**. 이 시스템에서는 처리량보다 파티션 내 순서가 중요하다.
4. 소비자는 `Inbox` 로 `(consumer, eventId)` 를 비즈니스 부수효과와 **같은 트랜잭션**에 기록해 중복을 흡수한다.
5. 실패 메시지는 지수 백오프 재시도 후 `<topic>.DLT` 로 격리한다(운영자 개입 지점).
6. `traceparent` 를 outbox 행에 저장했다가 Kafka 헤더로 전달해, 비동기 경계를 넘어도 분산 추적이 이어진다.

## 대안

- **Debezium CDC**: 릴레이 코드를 없앨 수 있지만 Kafka Connect 운영 부담. 서비스 수가 늘면 재검토.
- **Kafka 트랜잭션(exactly-once)**: DB 트랜잭션과 원자적으로 묶이지 않으므로 dual write 를 해결하지 못한다.

## 결과

- (+) 부하 테스트 후 미발행 0건, 릴레이 지연 p99 0.2s
- (+) 부분 인덱스로 폴링 비용이 발행 완료 행 수와 무관 (200만 행 기준 140ms → 0.08ms, docs/performance)
- (−) 폴링 주기만큼 지연(p50 ~200ms)이 생긴다 → 커밋 직후 릴레이를 깨우는 최적화를 향후 과제로 남김
