# n8n 운영 자동화

사람이 매번 확인하던 운영 절차 두 가지를 n8n 워크플로로 옮겼습니다. 워크플로는 JSON 으로 저장소에 두고, `scripts/verify-n8n.sh` 가 **실제 n8n 컨테이너에 import · 활성화 → 웹훅 실행 → Slack 에 도착한 메시지 내용**까지 확인합니다 (CI `n8n` 잡).

| 워크플로 | 트리거 | 흐름 |
|---|---|---|
| `dlt-alert-to-slack.json` | Datadog 모니터 `[BrewSlot] DLT 로 격리된 메시지 발생` 의 웹훅 (`@webhook-n8n-brewslot-dlt-alert`) | dlt-console `GET /dlt/topics` 로 **적체가 있는 토픽만** 골라 → 토픽별 건수와 조회 링크를 Slack 으로 전송. 이미 재처리돼 0건이면 "적체 없음"으로 알림 |
| `daily-settlement-report.json` | 매일 06:10 KST (Schedule) · 수동 실행 웹훅 `{"businessDate":"YYYY-MM-DD"}` | 전날(KST) 기준 `POST /reconciliation-runs`(PG 대사) → `POST /settlement-runs`(정산 마감, 멱등) → 불일치 유형별 건수 · 매장 수 · 지급 예정액을 Slack 요약 |

정산 마감은 멱등(같은 날짜 재실행 시 같은 정산서)이라, 스케줄이 두 번 돌거나 사람이 수동 웹훅으로 다시 돌려도 결과가 바뀌지 않습니다.

## 환경 변수

| 변수 | 예 |
|---|---|
| `DLT_CONSOLE_URL` | `http://dlt-console:8090` |
| `SETTLEMENT_URL` | `http://settlement-service:8084` |
| `SLACK_WEBHOOK_URL` | Slack Incoming Webhook URL |
| `N8N_BLOCK_ENV_ACCESS_IN_NODE` | `false` (노드에서 `$env` 사용) |
| `GENERIC_TIMEZONE` | `Asia/Seoul` |

## 검증

```bash
./scripts/verify-n8n.sh
```

`mock_endpoints.py` 가 dlt-console · settlement-service · Slack 웹훅을 실제 API 와 같은 응답 형식으로 흉내 내고, Slack 으로 들어온 본문을 파일에 기록합니다. 스크립트는 다음을 확인합니다.

- DLT 알림: `payment.events.DLT: 2건` 은 포함, 적체 0건인 `loyalty.commands.DLT` 는 제외
- 정산 보고: `2026-09-27 PG 대사 불일치 1건`, `정산서 2개 매장 · 지급 예정 152,374원`
