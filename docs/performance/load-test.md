# 부하 테스트 — 출근 러시 시나리오

> 스크립트: [`load-test/rush-hour.js`](../../load-test/rush-hour.js), [`load-test/hot-slot.js`](../../load-test/hot-slot.js) (k6)
> 환경: **4 vCPU / 16 GB 단일 머신**에 k6 + 서비스 4개(JVM) + PostgreSQL + Kafka + Redis 를 모두 올린 상태.
> 절대 수치보다 **병목을 찾고 고친 과정**에 의미가 있다.

## 시나리오

| 시나리오 | 부하 | 검증 |
|---|---|---|
| `browse` | 픽업 가능 시각 조회 200 req/s × 60s | p95 < 200ms |
| `order_and_pay` | 주문 생성 50 req/s × 60s → 성공 시 결제 시작 | 주문 p95 < 300ms, 결제 시작 p95 < 200ms, 오류율 < 1% |
| `hot-slot` | 100명이 **같은 매장·같은 픽업 시각**에 동시 주문 | 초과 예약 0건, 거절 응답에 대안 시각 포함 |

## 결과

### 1) API 지연 (웜업 후)

| API | p50 | p95 | p99 |
|---|---:|---:|---:|
| `GET /stores/{id}/pickup-times` (Redis 스냅샷) | 1.75 ms | 11.8 ms | 33.6 ms |
| `POST /orders` (슬롯 행 잠금 + 주문 저장 + Outbox) | 9.8 ms | 34.5 ms | 94.5 ms |
| `POST /orders/{id}/payment` (Saga 시작) | 4.2 ms | 18.6 ms | 47.9 ms |

- 300 req/s, 18,003 요청 **오류 0건**, 3,001건 주문 전부 `PAID` 로 종결.
- 슬롯 사용량 캐시: 35,889 hit / 5 rebuild / 0 fallback (Redis 조회가 DB 슬롯 잠금과 경합하지 않음).
- 콜드 스타트(JIT 전) 첫 실행은 p95 가 ~400ms 였다 — 측정은 웜업 후 수치를 사용했다.

### 2) 인기 시각 쏠림 (hot-slot)

| 결과 | 값 |
|---|---:|
| 동시 요청 | 100 |
| 수락 | **3** (에스프레소 용량 6 ÷ 핫라떼 부하 2 — 정확히 이론값) |
| 거절(409) 중 대안 시각 포함 | **97 / 97** |
| p95 | 194 ms (100건이 같은 행 잠금을 순서대로 통과) |
| 슬롯 원장 ↔ 예약 라인 불일치 | **0** |

### 3) 병목 발견과 수정: Saga 완료 시간 p95 13.7s → 0.46s

첫 측정에서 모든 결제는 결국 성공했지만, **결제 시작 → 주문 확정** 시간이 p50 0.34s / **p95 13.7s** 로 꼬리가 길었다.
구간별로 쪼개 원인을 찾았다 (주문 DB 와 결제 DB 의 타임스탬프를 주문 ID 로 조인):

| 구간 | p50 | p95 |
|---|---:|---:|
| Saga 시작 → payment-service 가 명령 소비 | 202 ms | **13,489 ms** |
| 명령 소비 → PG 승인 | 3 ms | 14 ms |
| PG 승인 → 주문 PAID | 143 ms | 351 ms |

- order-service Outbox 릴레이 지연은 `ChargePayment` 기준 p95 403 ms 로 정상 → **소비자 쪽 병목**.
- 원인: `@KafkaListener` 기본 동시성 1 — 파티션 3개를 **스레드 하나가** `ack-mode: record`(레코드마다 동기 커밋)로 처리.
- 수정: `spring.kafka.listener.concurrency: 3` (파티션 수와 동일). 같은 파티션은 여전히 한 스레드만 처리하므로
  **주문 단위(key=orderId) 순서 보장은 그대로**다.

| | 부하 | Saga 완료 p50 | p95 | p99 | 최종 PAID |
|---|---|---:|---:|---:|---:|
| Before (concurrency 1) | 결제 15~23 건/s (3회 실행 합산) | 336 ms | **13,739 ms** | 16,190 ms | 100% |
| After (concurrency 3) | 결제 **50 건/s** (2배) | 237 ms | **463 ms** | 688 ms | 100% |

p50 의 대부분(~200ms)은 Outbox 폴링 주기(200ms)다. 더 줄이려면 커밋 직후 릴레이를 깨우는 방식
(`TransactionSynchronization.afterCommit` → 즉시 relay 트리거, 폴링은 안전망)을 적용할 수 있다 — 다음 과제로 남겼다.

## 재현

```bash
docker compose up -d postgres kafka redis
./gradlew bootJar
for s in order payment loyalty settlement; do KAFKA_BOOTSTRAP=localhost:29092 java -jar services/$s-service/build/libs/$s-service-0.1.0.jar & done
docker run --rm --network host -e AHEAD_MINUTES=60 -e CAPACITY=1000 -v $PWD/load-test:/scripts grafana/k6 run /scripts/rush-hour.js
docker run --rm --network host -v $PWD/load-test:/scripts grafana/k6 run /scripts/hot-slot.js
```
> `AHEAD_MINUTES` 는 픽업 후보 시각 창의 시작(현재+N분)이다. 영업시간(데모 매장 00:05~23:59) 밖으로 넘어가면 422 가 정상 응답으로 나온다.
