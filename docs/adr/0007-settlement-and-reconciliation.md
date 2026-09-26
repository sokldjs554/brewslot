# ADR-0007. 정산: 돈의 사실만 적재, 늦은 이벤트는 이월, PG 건별 대사

- 상태: 채택
- 관련 코드: `settlement-service`

## 결정

1. **정산 원천은 "돈이 움직인 사실" 이벤트뿐**: `PaymentCaptured/Refunded`, `PointsRedeemed/RedemptionReversed`.
   주문 상태 이벤트로 정산하면 "결제 실패했는데 주문 이벤트는 있음" 같은 경우를 걸러야 한다.
   이벤트 ID 유니크 제약이 자연스러운 멱등 키다.
2. **거래일 = 한국 시간 기준 날짜**. 23:59 결제와 00:01 환불은 서로 다른 영업일이다(테스트로 고정).
3. **마감 후 도착한 과거 거래는 다음 정산서에 "이월분(carriedOverCount)" 으로 포함**한다. 이미 확정된 정산서를 수정하지 않는다.
   (확정된 정산서는 매장에 지급된 근거 문서이므로 불변이어야 한다.)
4. **PG 수수료는 건별 반올림 후 합산**. 합계에 요율을 한 번 곱하면 PG 정산 파일과 1원 단위로 어긋난다(테스트: 4,525원 × 2건 → 200원 vs 199원).
5. 플랫폼 수수료는 순매출 × 요율, 원 미만 절사(가맹점 유리). `payout = net - pgFee - platformFee` 는 DB CHECK 로도 강제.
6. **대사(reconciliation)**: PG 정산 파일과 원장을 PG 거래번호로 1:1 매칭해
   `MISSING_IN_LEDGER`(고객 돈은 나갔는데 우리 기록이 없음 — 가장 위험) / `MISSING_IN_PG` / `AMOUNT_MISMATCH` / `FEE_MISMATCH` 를 기록한다.
7. 금액은 원 단위 `Long`(`Money` value class). 비율 계산만 `BigDecimal` + **호출자가 명시한 반올림 정책**.

## 결과

- E2E: 정상/거절/타임아웃 시나리오 후 대사 불일치 0건
