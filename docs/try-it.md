# 5분 체험 가이드

> 이 문서는 Codespaces 를 열면 자동으로 열립니다. 서비스 4개 + PostgreSQL · Kafka · Redis 가 **자동으로 기동**되고,
> 터미널에 `✅ BrewSlot 준비 완료` 가 보이면 시작하면 됩니다 (첫 실행 약 3~5분).

## 1. 전체 시나리오를 한 번에 보기 (추천)

터미널에서:

```bash
./scripts/demo.sh
```

12개 장면이 설명과 함께 실행됩니다 — 주문 · 결제 Saga · 인기 시각 쏠림(409 + 대안 시각) · 바리스타 제조 큐 · 적립 ·
카드 거절 시 포인트 자동 복원 · **PG 응답 유실 복구** · 매장 대시보드 · 정산 · PG 대사.
(예시 출력: [demo.md](demo.md))

## 2. Swagger UI 에서 직접 눌러 보기

하단 **PORTS** 탭에서 8081 포트의 🌐 아이콘을 누르면 주문 서비스 Swagger UI 가 열립니다.
(결제 8082 · 포인트 8083 · 정산 8084)

| 순서 | API | 입력 예시 |
|---|---|---|
| ① 메뉴 보기 | `GET /stores/{storeId}` | `storeId = 101` — 메뉴별 스테이션 · 부하 · 신선도 |
| ② 받을 수 있는 시각 | `GET /stores/{storeId}/pickup-times` | `items = 1003:3` (핫 카페라떼 3잔) |
| ③ 주문 | `POST /orders` | 헤더 `X-Member-Id: 1`, `Idempotency-Key: try-it-0001` / 아래 본문 |
| ④ 같은 시각에 또 주문 | `POST /orders` | `Idempotency-Key` 만 바꿔서 → **409 + alternatives** |
| ⑤ 결제 | `POST /orders/{orderId}/payment` | `{"cardToken":"tok_visa"}` (`tok_declined` 거절, `tok_timeout` PG 응답 유실) |
| ⑥ 확인 | `GET /orders/{orderId}` | 잠시 후 `PAID` |

③의 본문 — `pickupAt` 은 ②의 응답에서 `feasible: true` 인 시각 하나를 복사해 넣습니다.

```json
{ "storeId": 101, "pickupAt": "②에서 복사", "items": [{ "menuItemId": 1003, "quantity": 3 }], "pointsToUse": 0 }
```

> 핫 카페라떼는 부하 2 · 신선도 5분이라 **픽업 직전 5분 슬롯 하나**에서만 만들 수 있고, 에스프레소 용량은 6입니다.
> 그래서 3잔 주문 하나로 그 시각이 가득 차고, 같은 시각의 다음 주문은 409 와 함께 가장 가까운 가능 시각 3개를 돌려받습니다.

## 3. 코드에서 먼저 볼 곳

| 무엇 | 어디 |
|---|---|
| 픽업 약속 스케줄러 (순수 함수) | `services/order-service/.../scheduling/domain/BrewScheduler.kt` |
| 초과 예약 방지 (정렬된 행 잠금) | `services/order-service/.../scheduling/infra/JdbcCapacityLedger.kt` |
| 결제 Saga | `services/order-service/.../ordering/saga/CheckoutSagaOrchestrator.kt` |
| PG 결과 불명 복구 | `services/payment-service/.../application/PaymentService.kt` |
| 복식부기 원장 + DB 트리거 | `services/loyalty-service/src/main/resources/db/migration/loyalty/V1__loyalty_schema.sql` |
| 설계 결정 9건 | `docs/adr/` |

## 4. 테스트 돌려 보기

```bash
./scripts/stop-all.sh                      # 데모용 인프라를 내리고
./gradlew build                            # 70개 테스트 (Testcontainers 로 DB·Kafka·Redis 를 직접 띄움, 약 5분)
```
