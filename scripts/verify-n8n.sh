#!/usr/bin/env bash
# n8n 워크플로(automation/n8n/*.json)를 실제 n8n 컨테이너에 import · 활성화한 뒤
# 웹훅으로 실행해, 모의 서버(dlt-console · settlement · Slack)에 기대한 Slack 메시지가 도착하는지 확인한다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${N8N_IMAGE:-n8nio/n8n:1.94.1}"
MOCK_PORT="${MOCK_PORT:-9911}"
N8N_PORT="${N8N_PORT:-5678}"
WORK="$(mktemp -d)"
OUT="$WORK/slack.jsonl"
NAME="brewslot-n8n-verify"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
}
trap cleanup EXIT

python3 "$ROOT/automation/n8n/mock_endpoints.py" "$MOCK_PORT" "$OUT" &
MOCK_PID=$!

ENV=(-e DLT_CONSOLE_URL="http://127.0.0.1:$MOCK_PORT"
     -e SETTLEMENT_URL="http://127.0.0.1:$MOCK_PORT"
     -e SLACK_WEBHOOK_URL="http://127.0.0.1:$MOCK_PORT/slack"
     -e N8N_BLOCK_ENV_ACCESS_IN_NODE=false
     -e GENERIC_TIMEZONE=Asia/Seoul
     -e N8N_PORT="$N8N_PORT"
     -e N8N_DIAGNOSTICS_ENABLED=false
     -e N8N_RUNNERS_ENABLED=false
     -e N8N_ENFORCE_SETTINGS_FILE_PERMISSIONS=false)

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker volume rm -f "$NAME" >/dev/null 2>&1 || true
docker volume create "$NAME" >/dev/null
VOL=(-v "$NAME:/home/node/.n8n" -v "$ROOT/automation/n8n:/workflows:ro")

echo "▶ 워크플로 import · 활성화"
for wf in dlt-alert-to-slack daily-settlement-report; do
  docker run --rm --network host "${ENV[@]}" "${VOL[@]}" "$IMAGE" import:workflow --input="/workflows/$wf.json"
done
docker run --rm --network host "${ENV[@]}" "${VOL[@]}" "$IMAGE" update:workflow --all --active=true

echo "▶ n8n 기동"
docker run -d --name "$NAME" --network host "${ENV[@]}" "${VOL[@]}" "$IMAGE" start >/dev/null
for i in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$N8N_PORT/healthz" >/dev/null 2>&1 && break
  sleep 2
done
sleep 3

echo "▶ 웹훅 실행"
curl -fsS -X POST "http://127.0.0.1:$N8N_PORT/webhook/brewslot-dlt-alert" \
  -H 'Content-Type: application/json' -d '{"title":"[P2] Kafka DLT 격리 발생"}'; echo
curl -fsS -X POST "http://127.0.0.1:$N8N_PORT/webhook/brewslot-settlement-run" \
  -H 'Content-Type: application/json' -d '{"businessDate":"2026-09-27"}'; echo

for i in $(seq 1 30); do
  [ -f "$OUT" ] && [ "$(wc -l < "$OUT")" -ge 2 ] && break
  sleep 1
done

echo "▶ Slack 으로 전송된 메시지"
cat "$OUT" 2>/dev/null || true

fail=0
grep -q 'payment.events.DLT: 2건' "$OUT" || { echo "✗ DLT 알림 메시지 없음"; fail=1; }
grep -q 'loyalty.commands.DLT' "$OUT" && { echo "✗ 적체 0건 토픽이 알림에 포함됨"; fail=1; }
grep -q '2026-09-27 PG 대사 불일치 1건' "$OUT" || { echo "✗ 대사 경고 메시지 없음"; fail=1; }
grep -q '정산서 2개 매장 · 지급 예정 152,374원' "$OUT" || { echo "✗ 정산 요약 메시지 없음"; fail=1; }
if [ "$fail" -ne 0 ]; then
  docker logs "$NAME" | tail -50
  exit 1
fi
docker volume rm -f "$NAME" >/dev/null 2>&1 || true
echo "✓ n8n 워크플로 2개 검증 통과"
