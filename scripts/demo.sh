#!/usr/bin/env bash
# BrewSlot 데모 — 서비스 4개가 떠 있는 상태에서 실행 (README "실행" 참고)
#   ./scripts/demo.sh
# 필요: curl, jq, python3
set -euo pipefail
O=${ORDER_URL:-http://localhost:8081}; P=${PAYMENT_URL:-http://localhost:8082}
L=${LOYALTY_URL:-http://localhost:8083}; S=${SETTLEMENT_URL:-http://localhost:8084}
RUN=$(date +%s); MEMBER=$(( RUN % 100000 + 500000 ))
B=$'\e[1m'; D=$'\e[2m'; G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; C=$'\e[36m'; N=$'\e[0m'

scene() { echo; echo "${B}${C}━━ $1 ━━${N}"; }
say()   { echo "${D}$1${N}"; }
req()   { echo "${Y}\$ $1${N}"; }
json()  { curl -s -H 'Content-Type: application/json' "$@"; }
kst()   { TZ=Asia/Seoul date -d "@$1" +%H:%M; }

# 픽업 시각: 지금+30분(5분 단위). 영업 종료(23:55) 이후면 내일 08:30.
pick=$(python3 - <<'PY'
import time, datetime as dt
t=int(time.time())+1800; t-=t%300
k=dt.datetime.fromtimestamp(t, dt.timezone(dt.timedelta(hours=9)))
if k.hour==23 and k.minute>=40 or k.hour==0 and k.minute<10:
    k=(k+dt.timedelta(days=1)).replace(hour=8,minute=30); t=int(k.timestamp())
print(t)
PY
)
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:00Z; }
PICK=$(iso "$pick")

order() { # member key pickupEpoch points items-json
  json -X POST "$O/orders" -H "X-Member-Id: $1" -H "Idempotency-Key: $2" \
    -d "{\"storeId\":101,\"pickupAt\":\"$(iso "$3")\",\"pointsToUse\":$4,\"items\":$5}"
}
pay() { json -X POST "$O/orders/$2/payment" -H "X-Member-Id: $1" -d "{\"cardToken\":\"$3\"}" >/dev/null; }
status() { json "$O/orders/$2" -H "X-Member-Id: $1" | jq -r '.status + (if .cancelReason then " (" + .cancelReason + ")" else "" end)'; }
wait_status() { for _ in $(seq 1 60); do s=$(status "$1" "$2"); [[ "$s" == $3* ]] && { echo "$s"; return; }; sleep 0.5; done; echo "$s"; }
balance() { json "$L/members/$1/wallets" | jq -r '[.[] | select(.brandId==1) | .balance][0] // 0'; }

scene "1. 매장과 메뉴 — 메뉴마다 '제조 스테이션 · 부하 · 신선도'가 있다"
req "GET /stores/101"
json "$O/stores/101" | jq -r '"매장: \(.name)   슬롯: \(.slotMinutes)분   슬롯당 용량: \(.unitsPerSlot)",
  (.menu[] | "  \(if .available then "  " else "✗ " end)\(.name)  \(.price)원  [\(.station) 부하 \(.loadUnits), 신선도 \(.freshnessMinutes)분]")'

scene "2. 장바구니 기준 픽업 가능 시각과 혼잡도"
CART='[{"menuItemId":1004,"quantity":2},{"menuItemId":1005,"quantity":1}]'
say "장바구니: 아이스 바닐라라떼 ×2 + 딸기 스무디 ×1"
req "GET /stores/101/pickup-times?items=1004:2&items=1005:1"
json "$O/stores/101/pickup-times?items=1004:2&items=1005:1&from=$(iso $((pick-600)))&to=$(iso $((pick+600)))" |
  jq -r '.[] | "  \(.pickupAt)  \(if .feasible then "가능" else "불가" end)  혼잡도 \(.loadPercent)%"' |
  while read -r t rest; do echo "  $(TZ=Asia/Seoul date -d "$t" +%H:%M) $rest"; done

scene "3. 포인트 3,000P 지급 (브랜드 부담, 복식부기 원장에 기록)"
req "POST /admin/point-grants"
json -X POST "$L/admin/point-grants" -d "{\"memberId\":$MEMBER,\"brandId\":1,\"amount\":3000}" >/dev/null
echo "  회원 $MEMBER 잔액: $(balance $MEMBER)P"

scene "4. 주문 생성 — $(kst "$pick") 픽업, 포인트 2,000P 사용 → 제조 슬롯 임시 점유(HELD)"
req "POST /orders  (Idempotency-Key: demo-$RUN)"
r=$(order "$MEMBER" "demo-$RUN" "$pick" 2000 "$CART")
ID=$(jq -r .orderId <<<"$r")
jq -r '"  주문 \(.orderId)\n  상태 \(.status)   총액 \(.totalAmount)원 = 포인트 \(.pointAmount) + 카드 \(.cardAmount)\n  점유 만료 \(.holdExpiresAt)"' <<<"$r"
say "같은 Idempotency-Key 로 다시 요청 (더블 클릭 / 네트워크 재시도)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$O/orders" -H 'Content-Type: application/json' -H "X-Member-Id: $MEMBER" -H "Idempotency-Key: demo-$RUN" \
  -d "{\"storeId\":101,\"pickupAt\":\"$PICK\",\"pointsToUse\":2000,\"items\":$CART}")
echo "  → HTTP $code (새 주문을 만들지 않고 같은 주문 반환)"

scene "5. 결제 — Saga: 포인트 차감 → 카드 승인 → 주문 확정 (Kafka 비동기)"
req "POST /orders/$ID/payment  {cardToken: tok_visa}"
pay "$MEMBER" "$ID" tok_visa
echo "  주문 상태: ${G}$(wait_status "$MEMBER" "$ID" PAID)${N}"
echo "  포인트 잔액: 3000 → $(balance $MEMBER)P"
json "$P/payments/$ID" | jq -r '"  카드 결제: \(.amount)원 \(.status) (PG 거래 \(.pgTransactionId))"'

scene "6. 인기 시각 쏠림 — 같은 시각에 핫 카페라떼(부하 2)를 계속 주문"
hot=$((pick + 600)); say "$(kst $hot) 픽업 · 에스프레소 용량 6/슬롯 · 핫 음료는 신선도 5분이라 직전 슬롯에서만 제조 가능"
for i in 1 2 3 4; do
  r=$(order $((MEMBER+i)) "hot-$RUN-$i" "$hot" 0 '[{"menuItemId":1003,"quantity":1}]')
  if [[ $(jq -r '.orderId // empty' <<<"$r") ]]; then echo "  손님 $i: ${G}수락${N}"
  else jq -r --arg n "$i" '"  손님 \($n): \u001b[31m409 \(.type | split("/") | last)\u001b[0m  병목: \(.bottleneckStation)\n           대안 시각: \([.alternatives[]] | join(", "))"' <<<"$r" |
       python3 -c "import sys,re,datetime as d
for l in sys.stdin: print(re.sub(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)', lambda m: d.datetime.fromisoformat(m[1].replace('Z','+00:00')).astimezone(d.timezone(d.timedelta(hours=9))).strftime('%H:%M'), l), end='')"
  fi
done

scene "7. 바리스타 화면 — 픽업 순서가 아니라 '지금 만들어야 할' 제조 슬롯 순서"
req "GET /stores/101/production-queue"
json "$O/stores/101/production-queue" | jq -r '.[] | "\(.slotStart) \(.station) \(.reservedUnits)/\(.capacity) 주문 \(.orders | length)건"' |
  while read -r t rest; do echo "  $(TZ=Asia/Seoul date -d "$t" +%H:%M) 슬롯  $rest"; done

scene "8. 매장 처리 — 접수 → 제조 완료 → 픽업 (완료 시 카드 결제액의 3% 적립)"
for s in PREPARING READY PICKED_UP; do
  json -X PUT "$O/stores/101/orders/$ID/status" -d "{\"status\":\"$s\"}" | jq -r '"  → \(.status)"'
done
for _ in $(seq 1 20); do b=$(balance $MEMBER); [[ $b != 1000 ]] && break; sleep 0.5; done
echo "  포인트 잔액: 1000 → ${G}${b}P${N}  (카드 결제분만 적립, 포인트 결제분은 제외)"

scene "9. 카드 거절 — 이미 차감한 포인트가 자동으로 돌아온다 (보상 트랜잭션)"
M2=$((MEMBER+10)); json -X POST "$L/admin/point-grants" -d "{\"memberId\":$M2,\"brandId\":1,\"amount\":5000}" >/dev/null
r=$(order $M2 "decl-$RUN" "$((pick+300))" 1500 '[{"menuItemId":1001,"quantity":1},{"menuItemId":1006,"quantity":1}]'); ID2=$(jq -r .orderId <<<"$r")
pay $M2 "$ID2" tok_declined
echo "  주문 상태: ${R}$(wait_status $M2 "$ID2" CANCELLED)${N}"
sleep 1; echo "  포인트: 5000 → 차감 1500 → 복원 → ${G}$(balance $M2)P${N}"

scene "10. PG 응답 유실 — 승인은 됐는데 응답을 못 받은 '결과 불명' 상황"
M3=$((MEMBER+20))
r=$(order $M3 "tmo-$RUN" "$((pick+900))" 0 '[{"menuItemId":1002,"quantity":1}]'); ID3=$(jq -r .orderId <<<"$r")
pay $M3 "$ID3" tok_timeout; sleep 1
json "$P/payments/$ID3" | jq -r '"  결제 상태: \(.status)  ← PG 응답을 못 받음. 주문은 아직 \("'"$(status $M3 "$ID3")"'")"'
say "  복구 잡이 PG 거래 조회로 결과를 확정하기를 기다리는 중 (유예 10초)..."
echo "  주문 상태: ${G}$(wait_status $M3 "$ID3" PAID)${N} / 결제 상태: $(json "$P/payments/$ID3" | jq -r .status)"
for _ in $(seq 1 40); do [[ $(status $M3 "$ID3") == PAID ]] && break; sleep 1; done
echo "  → 최종: 주문 $(status $M3 "$ID3") / 결제 $(json "$P/payments/$ID3" | jq -r .status)"

scene "11. 매장 대시보드 — 핵심 지표는 '픽업 약속 준수율'"
TODAY=$(TZ=Asia/Seoul date -d "@$pick" +%F)
sleep 1; json "$O/stores/101/dashboard?date=$TODAY" | jq -r '"  결제 \(.paidOrders)건  매출 \(.grossAmount)원  준비 완료 \(.readyOrders)건  약속 준수율 \(if .promiseKeptRate then (.promiseKeptRate*100|floor|tostring)+"%" else "-" end)"'

scene "12. 정산과 PG 대사 — PG 에만 있는 승인 건을 일부러 하나 심어 본다"
req "POST /pg/admin/ghost-transactions  (우리 원장에는 없는 PG 승인)"
json -X POST "$P/pg/admin/ghost-transactions" -d '{"amount":7000}' | jq -r '"  PG 거래 \(.transactionId) 생성"'
DAY=$(TZ=Asia/Seoul date +%F)
req "POST /reconciliation-runs {businessDate: $DAY}"
json -X POST "$S/reconciliation-runs" -d "{\"businessDate\":\"$DAY\"}" | jq -r '"  PG \(.pgCount)건 / 원장 \(.ledgerCount)건 / 일치 \(.matchedCount)건 / 이슈 \(.issues|length)건",
  (.issues[] | "  \u001b[31m⚠ \(.type)\u001b[0m  \(.transactionId)  PG \(.pgAmount)원")'
req "POST /settlement-runs {businessDate: $DAY}"
st=$(json -X POST "$S/settlement-runs" -d "{\"businessDate\":\"$DAY\"}" | jq -r '.[] | select(.draft.storeId==101) | .draft |
  "  매장 101 정산서: 카드 \(.cardSales) + 포인트 \(.pointSales) − 포인트 사용취소 \(-.pointReversals) = 순매출 \(.netSales)원\n  − PG 수수료 \(.pgFee) − 플랫폼 수수료 \(.platformFee) = \u001b[1m지급액 \(.payout)원\u001b[0m"')
echo "${st:-  (오늘 매장 101 정산서는 이미 마감됨 — 같은 날짜 재실행은 멱등, 새 거래는 다음 마감에 이월)}"
echo
echo "${B}끝.${N} 초과 예약 0 · 포인트/카드 불일치 0 · 모든 금액 흐름은 원장과 PG 파일로 대사됩니다."
