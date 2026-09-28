# Datadog 모니터링 (코드로 관리)

서비스는 Micrometer 로 지표를 내고, `DATADOG_ENABLED=true DD_API_KEY=...` 로 켜면 Datadog 으로 보낸다
(`management.datadog.metrics.export`, 각 서비스 yml). 로컬에서는 같은 지표를 `/actuator/prometheus` 로 본다.

| 파일 | 내용 |
|---|---|
| `dashboard.json` | 고객 체감(결제 Saga p50/p95, 픽업 약속 지연) → 원인 후보(Outbox 적체·지연, DLT) → 비즈니스(결제 결과, 쿠폰 발급) 순서의 대시보드 |
| `monitors.json` | 알림 6종: Saga p95 > 3초, Saga 타임아웃, Outbox 적체, DLT 1건 이상, 픽업 약속 p90 지연 > 60초, Redis 폴백 |
| `check_metrics.py` | 대시보드·모니터가 참조하는 지표 이름이 코드에 실제로 있는지 CI 에서 검사 (지표 이름을 바꾸고 알림을 안 고쳐 "데이터 없음" 이 되는 사고 방지) |

## 적용

```bash
# 대시보드
curl -X POST "https://api.datadoghq.com/api/v1/dashboard" \
  -H "DD-API-KEY: $DD_API_KEY" -H "DD-APPLICATION-KEY: $DD_APP_KEY" -H "Content-Type: application/json" \
  -d @infra/datadog/dashboard.json

# 모니터 (배열을 하나씩)
jq -c '.[]' infra/datadog/monitors.json | while read -r m; do
  curl -X POST "https://api.datadoghq.com/api/v1/monitor" \
    -H "DD-API-KEY: $DD_API_KEY" -H "DD-APPLICATION-KEY: $DD_APP_KEY" -H "Content-Type: application/json" -d "$m"
done
```

## 지표 설계 메모

- **결제 Saga 소요 시간**(`brewslot.checkout.saga.duration`, outcome 태그): 부하 테스트에서 p95 13.7초 병목을 찾은 지표를 상시 지표로 올렸다.
- **Outbox 미발행 적체**(`brewslot.outbox.pending`): 지연(lag)은 발행된 뒤에야 잴 수 있어, Kafka 가 멈추면 오히려 조용해진다. 적체량은 멈춘 순간부터 오른다.
- **DLT**(`brewslot.kafka.dlt`): 재시도 후에도 처리 못 한 메시지. 자동 복구되지 않는 유일한 경로라 1건이면 알린다.
- **픽업 약속 지연**(`brewslot.pickup.promise.lateness`): 서비스의 핵심 약속. 매장 용량 설정이 실제 인력보다 높다는 신호이기도 하다.

> 이 저장소는 실제 Datadog 계정에서 운영한 기록이 아니라, 운영을 시작할 때 바로 적용할 수 있게 정의·검증해 둔 설정이다.
