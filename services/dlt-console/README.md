# dlt-console — DLT 재처리 콘솔 (Python · FastAPI)

소비자가 재시도 후에도 처리하지 못한 메시지는 `<토픽>.DLT` 로 격리된다. 자동으로 복구되지 않는 유일한 경로라
Datadog 모니터(`brewslot.kafka.dlt` > 0)가 알리고, 운영자는 원인을 고친 뒤 이 콘솔로 재처리한다.

| API | 설명 |
|---|---|
| `GET /dlt/topics` | DLT 토픽별 적체 수 |
| `GET /dlt/{topic}/messages?limit=50` | 최근 메시지 — 원래 토픽, 실패 원인(예외 메시지), eventType·eventId |
| `POST /dlt/{topic}/messages/{partition}/{offset}/replay` | 원래 토픽으로 같은 key·value 재발행 (`X-Ops-Token` 필요, `X-Operator` 기록) |
| `GET /metrics` | `brewslot_dlt_replays_total{topic,result}` |

## 안전장치
- **소비자 멱등성**: 모든 소비자는 Inbox(consumer, eventId)로 중복을 거른다. 이미 처리된 메시지를 재처리해도 한 번만 반영된다.
- **중복 재처리 방지**: 재처리 기록을 compacted 토픽 `ops.dlt-replays` 에 남기고, 같은 메시지를 다시 보내려 하면 409. 의도적이면 `force=true`.
- **조회가 서비스에 영향 없음**: 소비자 그룹 없이 파티션을 직접 배정해 읽으므로 서비스의 오프셋을 건드리지 않는다.
- **추적**: 재발행 메시지에 `brewslot-replayed-from`, `brewslot-replay-id` 헤더.

## 실행 · 테스트
```bash
pip install -e '.[dev]'
pytest -q                     # 단위 9 (FakeBroker) + 통합 1 (Testcontainers Kafka, Docker 필요)
OPS_TOKEN=... KAFKA_BOOTSTRAP=localhost:29092 uvicorn dlt_console.app:app --port 8090   # /docs 에 OpenAPI
```
