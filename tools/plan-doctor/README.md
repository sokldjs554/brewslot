# plan-doctor

`QueryPlanRegressionTest` 가 남긴 PostgreSQL 실행계획(`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`)을 진단한다.

```bash
pip install -e '.[dev]'
python -m plan_doctor.cli '../../services/*/build/query-plans/*.json'                 # 규칙 기반 리포트 (Markdown)
python -m plan_doctor.cli '../../services/*/build/query-plans/*.json' --fail-on high  # CI 게이트
python -m plan_doctor.cli '../../services/*/build/query-plans/*.json' --ai \
  --schema '../../services/*/src/main/resources/db/**/*.sql'                         # + Claude 리뷰 (ANTHROPIC_API_KEY)
```

| 규칙 | 심각도 | 의미 |
|---|---|---|
| `SEQ_SCAN_LARGE` | high | 큰 테이블 순차 스캔 |
| `SORT_SPILL` | high | 정렬이 디스크로 넘침 |
| `SORT_LARGE` | medium | 많은 행을 읽어 메모리 정렬 (Top-N 을 인덱스 순서로 못 읽음) |
| `FILTER_WASTE` | medium | 읽은 행 대부분을 필터로 버림 |
| `ROW_MISESTIMATE` | low | 예상/실제 행 수 10배 이상 차이 (LIMIT 하위 노드는 제외 — 조기 종료) |
| `HEAP_FETCHES` | low | Index Only Scan 인데 힙 접근이 많음 (visibility map) |

`--ai` 는 규칙 결과·실행계획·스키마를 Claude(`claude-opus-5`, adaptive thinking)에 보내 원인 설명과 인덱스/쿼리 개선안, 그 대가를 받는다.
스키마는 프롬프트 캐싱 대상이며, 거절(refusal) 시 서버 측 폴백(`fallbacks: "default"`)을 사용하고, 그래도 거절되면 규칙 결과만 남긴다.
판단의 핵심은 결정적인 규칙 엔진이고, Claude 는 설명과 제안을 맡는다 — 자동 적용하지 않는다.
