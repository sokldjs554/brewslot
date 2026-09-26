#!/usr/bin/env bash
# 쿼리 튜닝 실험 재현 스크립트: 임시 PostgreSQL 16 컨테이너에 대용량 데이터를 넣고 before/after 실행계획을 뽑는다.
# 사용법: ./scripts/query-lab/run.sh > docs/performance/query-lab-output.txt
set -euo pipefail
cd "$(dirname "$0")"
NAME=brewslot-query-lab
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=lab -e POSTGRES_DB=lab \
  --shm-size=1g postgres:16-alpine -c shared_buffers=512MB -c work_mem=16MB >/dev/null
until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2
docker exec -i "$NAME" psql -q -U postgres -d lab < seed.sql >&2
# 캐시를 데운 뒤 측정(두 번째 실행 결과를 사용)
docker exec -i "$NAME" psql -q -U postgres -d lab < experiments.sql >/dev/null 2>&1 || true
docker exec -i "$NAME" psql -q -U postgres -d lab -c "DROP INDEX IF EXISTS lab.exp1_member_placed, lab.exp2_partial, lab.exp3_unpublished, lab.exp4_usable, lab.exp5_open;" >/dev/null
docker exec -i "$NAME" psql -U postgres -d lab < experiments.sql
docker rm -f "$NAME" >/dev/null
