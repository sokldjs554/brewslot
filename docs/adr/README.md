# Architecture Decision Records

| # | 결정 | 상태 |
|---|---|---|
| [0001](0001-promise-based-slot-scheduling.md) | 픽업 시간을 "주문 건수" 가 아니라 **스테이션별 제조 부하 + 신선도 창** 으로 스케줄링한다 | 채택 |
| [0002](0002-slot-capacity-concurrency.md) | 슬롯 용량의 원천은 PostgreSQL, **정렬된 행 잠금**으로 초과 예약을 막는다 (Redis Lua 기각) | 채택 |
| [0003](0003-transactional-outbox.md) | 모든 메시지는 **Transactional Outbox** 로만 발행, 릴레이는 advisory lock 으로 단일화 | 채택 |
| [0004](0004-caching-strategy.md) | 조회 경로만 캐시한다: 슬롯 사용량은 Redis(버전 CAS), 카탈로그는 로컬 Caffeine | 채택 |
| [0005](0005-checkout-saga-orchestration.md) | 결제는 **Orchestration Saga**, 순서는 포인트 → 카드, 보상은 멱등 명령 | 채택 |
| [0006](0006-double-entry-point-ledger.md) | 포인트는 **복식부기 원장 + 만료 lot**, 잔액은 유도값 | 채택 |
| [0007](0007-settlement-and-reconciliation.md) | 정산은 "돈이 움직인 사실" 이벤트만 적재, 늦은 이벤트는 **이월**, PG 파일과 **건별 대사** | 채택 |
| [0008](0008-explicit-sql-over-jpa.md) | JPA 대신 **JdbcClient + 명시적 SQL**, 도메인 모델은 프레임워크 무의존 | 채택 |
| [0009](0009-monorepo-multiservice.md) | 모노레포 멀티 서비스 + **한 JVM 에서 4개 서비스를 띄우는 E2E** | 채택 |
