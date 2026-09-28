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
| [0010](0010-coupon-promotion.md) | 쿠폰 이벤트: **선착순은 조건부 UPDATE**, 쿠폰은 포인트와 **한 로컬 트랜잭션**, 할인액은 **고객이 본 금액을 결제 때 검증** | 채택 |
| [0011](0011-realtime-notification-webflux-sse.md) | 실시간 알림은 **WebFlux SSE** 별도 서비스, 파드마다 **브로드캐스트 소비**, `Last-Event-ID` 로 재연결 이어받기 | 채택 |
| [0012](0012-dlt-replay-console.md) | DLT 재처리는 **FastAPI 콘솔**(원 토픽 재발행 · 중복 재처리 409), 알림은 **n8n** | 채택 |
| [0013](0013-store-transfer.md) | 매장 변경: **새 자리를 먼저 잡고** 새 매장이 수락하는 순간 **한 트랜잭션에서** 옮김. 실패는 모두 원래 주문 유지, 돈은 귀속만 이동 | 채택 |
| [0014](0014-proactive-transfer.md) | 매장 변경을 실제로 쓰게: **지연을 감지해 먼저 알리고**, 도보 15분 이내 매장만 가까운 순, **자동 수락 매장은 한 번에 이동** | 채택 |
