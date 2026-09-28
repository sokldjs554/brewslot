#!/usr/bin/env bash
# kind 에 배포된 BrewSlot 스모크 테스트:
#   모든 워크로드 Ready → 주문 → 결제 Saga → PAID → 고객 SSE 알림 수신 → DLT 콘솔 응답
#   → order-service 롤링 재시작 후에도 주문·결제가 이어지는지
# 필요: kubectl, curl, jq
set -euo pipefail
NS=brewslot
WORK="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT
step() { echo; echo "▶ $*"; }

wait_ready() {
  for kind in statefulset deployment; do
    for w in $(kubectl -n $NS get $kind -o name); do
      kubectl -n $NS rollout status "$w" --timeout=600s
    done
  done
}

forward() { # svc localPort remotePort
  kubectl -n $NS port-forward "svc/$1" "$2:$3" >"$WORK/pf-$1.log" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 30); do curl -s -o /dev/null "http://127.0.0.1:$2/" && return; sleep 1; done
  echo "port-forward $1 실패"; cat "$WORK/pf-$1.log"; exit 1
}

O=http://127.0.0.1:18081
order_and_pay() { # member key → orderId (PAID 까지 대기)
  local member=$1 key=$2 pick r id s
  pick=$(curl -fsS "$O/stores/101/pickup-times?items=1004:1" | jq -r '[.[] | select(.feasible)][0].pickupAt')
  [[ "$pick" != null ]] || { echo "가능한 픽업 시각 없음"; exit 1; }
  r=$(curl -fsS -X POST "$O/orders" -H 'Content-Type: application/json' -H "X-Member-Id: $member" -H "Idempotency-Key: $key" \
      -d "{\"storeId\":101,\"pickupAt\":\"$pick\",\"pointsToUse\":0,\"items\":[{\"menuItemId\":1004,\"quantity\":1}]}")
  id=$(jq -r .orderId <<<"$r")
  echo "  주문 $id  픽업 $pick  상태 $(jq -r .status <<<"$r")" >&2
  curl -fsS -o /dev/null -X POST "$O/orders/$id/payment" -H 'Content-Type: application/json' -H "X-Member-Id: $member" -d '{"cardToken":"tok_visa"}'
  for _ in $(seq 1 60); do
    s=$(curl -fsS "$O/orders/$id" -H "X-Member-Id: $member" | jq -r .status)
    [[ "$s" == PAID ]] && { echo "  → $s" >&2; echo "$id"; return; }
    sleep 1
  done
  echo "  → 결제 확정 안 됨: $s" >&2; exit 1
}

step "워크로드 Ready 대기"
wait_ready
kubectl -n $NS get pods -o wide

forward order-service 18081 8081
forward notification-service 18085 8085
forward dlt-console 18090 8090

MEMBER=$(( $(date +%s) % 100000 + 700000 ))
step "고객 앱 SSE 연결 (회원 $MEMBER)"
curl -sN -H "X-Member-Id: $MEMBER" http://127.0.0.1:18085/notifications/stream >"$WORK/sse.txt" &
PIDS+=($!)
sleep 8   # 알림 서비스는 latest 부터 읽으므로 파티션 할당을 기다린다

step "주문 → 결제 Saga (쿠폰·포인트 없음 → 카드 승인) → PAID"
order_and_pay "$MEMBER" "k8s-smoke-$MEMBER-1" >/dev/null

step "SSE 로 결제 확정 알림 수신 확인"
for _ in $(seq 1 30); do grep -q 'event:OrderPaid' "$WORK/sse.txt" && break; sleep 1; done
grep -A3 'event:OrderPaid' "$WORK/sse.txt" || { echo "✗ OrderPaid 알림 없음"; cat "$WORK/sse.txt"; exit 1; }

step "DLT 콘솔"
curl -fsS http://127.0.0.1:18090/dlt/topics; echo

step "order-service 롤링 재시작 (maxUnavailable 0 · preStop · graceful shutdown)"
kubectl -n $NS rollout restart deployment/order-service
kubectl -n $NS rollout status deployment/order-service --timeout=300s
kill "${PIDS[0]}" 2>/dev/null || true
forward order-service 18082 8081
O=http://127.0.0.1:18082
order_and_pay "$MEMBER" "k8s-smoke-$MEMBER-2" >/dev/null

step "HPA · PDB"
kubectl -n $NS get hpa,pdb
echo; echo "✓ Kubernetes 스모크 테스트 통과"
