# 데모 — 실제 실행 결과

서비스 4개 + PostgreSQL · Kafka · Redis 를 띄운 뒤 `./scripts/demo.sh` 를 실행한 출력 그대로다 (빈 DB에서 1회 실행, 색상 코드만 제거).
주문 → 결제 Saga → 인기 시각 쏠림 → 제조 → 적립 → 카드 거절 보상 → PG 응답 유실 복구 → 정산 · 대사까지 12개 장면.

```text

━━ 1. 매장과 메뉴 — 메뉴마다 '제조 스테이션 · 부하 · 신선도'가 있다 ━━
$ GET /stores/101
매장: 블루아워커피 역삼점   슬롯: 5분   슬롯당 용량: {"ESPRESSO":6,"BLENDER":3,"BREW_BAR":2}
    아메리카노(HOT)  3500원  [ESPRESSO 부하 1, 신선도 5분]
    아이스 아메리카노  3500원  [ESPRESSO 부하 1, 신선도 10분]
    카페라떼(HOT)  4500원  [ESPRESSO 부하 2, 신선도 5분]
    아이스 바닐라라떼  5000원  [ESPRESSO 부하 2, 신선도 10분]
    딸기 스무디  6000원  [BLENDER 부하 3, 신선도 10분]
    핸드드립(에티오피아)  6500원  [BREW_BAR 부하 2, 신선도 10분]
  ✗ 시즌 한정 밤라떼  6000원  [ESPRESSO 부하 2, 신선도 5분]

━━ 2. 장바구니 기준 픽업 가능 시각과 혼잡도 ━━
장바구니: 아이스 바닐라라떼 ×2 + 딸기 스무디 ×1
$ GET /stores/101/pickup-times?items=1004:2&items=1005:1
  23:05 가능  혼잡도 0%
  23:10 가능  혼잡도 0%
  23:15 가능  혼잡도 0%
  23:20 가능  혼잡도 0%
  23:25 가능  혼잡도 0%

━━ 3. 포인트 3,000P 지급 (브랜드 부담, 복식부기 원장에 기록) ━━
$ POST /admin/point-grants
  회원 530381 잔액: 3000P

━━ 4. 주문 생성 — 23:15 픽업, 포인트 2,000P 사용 → 제조 슬롯 임시 점유(HELD) ━━
$ POST /orders  (Idempotency-Key: demo-1790430381)
  주문 01a0ddf7-8a11-7c1e-a9d6-903c609a21f9
  상태 PENDING_PAYMENT   총액 16000원 = 포인트 2000 + 카드 14000
  점유 만료 2026-09-26T13:51:22.607340027Z
같은 Idempotency-Key 로 다시 요청 (더블 클릭 / 네트워크 재시도)
  → HTTP 200 (새 주문을 만들지 않고 같은 주문 반환)

━━ 5. 결제 — Saga: 포인트 차감 → 카드 승인 → 주문 확정 (Kafka 비동기) ━━
$ POST /orders/01a0ddf7-8a11-7c1e-a9d6-903c609a21f9/payment  {cardToken: tok_visa}
  주문 상태: PAID
  포인트 잔액: 3000 → 1000P
  카드 결제: 14000원 CAPTURED (PG 거래 pg_approve_a2372d1cf2c949b69d4b)

━━ 6. 인기 시각 쏠림 — 같은 시각에 핫 카페라떼(부하 2)를 계속 주문 ━━
23:25 픽업 · 에스프레소 용량 6/슬롯 · 핫 음료는 신선도 5분이라 직전 슬롯에서만 제조 가능
  손님 1: 수락
  손님 2: 수락
  손님 3: 수락
  손님 4: 409 pickup-slot-unavailable  병목: ESPRESSO
           대안 시각: 23:20, 23:30, 23:35

━━ 7. 바리스타 화면 — 픽업 순서가 아니라 '지금 만들어야 할' 제조 슬롯 순서 ━━
$ GET /stores/101/production-queue
  23:10 슬롯  BLENDER 3/3 주문 1건
  23:10 슬롯  ESPRESSO 4/6 주문 1건

━━ 8. 매장 처리 — 접수 → 제조 완료 → 픽업 (완료 시 카드 결제액의 3% 적립) ━━
  → PREPARING
  → READY
  → PICKED_UP
  포인트 잔액: 1000 → 1420P  (카드 결제분만 적립, 포인트 결제분은 제외)

━━ 9. 카드 거절 — 이미 차감한 포인트가 자동으로 돌아온다 (보상 트랜잭션) ━━
  주문 상태: CANCELLED (PAYMENT_DECLINED)
  포인트: 5000 → 차감 1500 → 복원 → 5000P

━━ 10. PG 응답 유실 — 승인은 됐는데 응답을 못 받은 '결과 불명' 상황 ━━
  결제 상태: UNKNOWN  ← PG 응답을 못 받음. 주문은 아직 PAYMENT_IN_PROGRESS
  복구 잡이 PG 거래 조회로 결과를 확정하기를 기다리는 중 (유예 10초)...
  주문 상태: PAID / 결제 상태: CAPTURED
  → 최종: 주문 PAID / 결제 CAPTURED

━━ 11. 매장 대시보드 — 핵심 지표는 '픽업 약속 준수율' ━━
  결제 2건  매출 19500원  준비 완료 1건  약속 준수율 100%

━━ 12. 정산과 PG 대사 — PG 에만 있는 승인 건을 일부러 하나 심어 본다 ━━
$ POST /pg/admin/ghost-transactions  (우리 원장에는 없는 PG 승인)
  PG 거래 pg_approve_908eeb34ca234b7c8cd1 생성
$ POST /reconciliation-runs {businessDate: 2026-09-26}
  PG 3건 / 원장 2건 / 일치 2건 / 이슈 1건
  ⚠ MISSING_IN_LEDGER  pg_approve_908eeb34ca234b7c8cd1  PG 7000원
$ POST /settlement-runs {businessDate: 2026-09-26}
  매장 101 정산서: 카드 17500 + 포인트 3500 − 포인트 사용취소 1500 = 순매출 19500원
  − PG 수수료 385 − 플랫폼 수수료 195 = 지급액 18920원

끝. 초과 예약 0 · 포인트/카드 불일치 0 · 모든 금액 흐름은 원장과 PG 파일로 대사됩니다.
```
