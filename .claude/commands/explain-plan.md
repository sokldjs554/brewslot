---
description: 실행계획 회귀 테스트를 돌리고 plan-doctor 로 진단한 뒤, 개선안을 제시한다
argument-hint: [서비스명 (기본: order-service)]
---

1. `./gradlew :services:${ARGUMENTS:-order-service}:test --tests '*QueryPlanRegressionTest*'` 를 실행한다.
2. `cd tools/plan-doctor && python -m plan_doctor.cli '../../services/*/build/query-plans/*.json'` 로 규칙 기반 진단을 본다.
3. 경고가 있는 쿼리마다 실행계획 노드(행 수, Buffers, 시간)를 근거로 원인을 설명한다.
   추측이면 추측이라고 쓰고, 확인용 SQL(예: `pg_stats` 조회, 다른 파라미터로 EXPLAIN)을 함께 낸다.
4. 개선안은 정확한 `CREATE INDEX` / 바뀐 SQL 과 그 대가(인덱스 크기, 쓰기 증폭, 다른 쿼리 영향)를 함께 제시한다.
5. 채택하면 새 Flyway 마이그레이션 + `QueryPlanRegressionTest` 단언 추가 + `docs/performance/query-plans.md` 표 갱신까지 한 PR 로 만든다.
   수치는 `scripts/query-lab` 으로 before/after 를 실제로 측정한 값만 쓴다.
