#!/usr/bin/env bash
# 이미 커밋된 Flyway 마이그레이션(V*.sql)이 수정·삭제·이름 변경되면 실패한다.
# 로컬에서는 .claude/hooks/guard-migrations.sh 가 Claude Code 의 편집을 막지만, 스크립트·사람의 편집은 훅을 거치지 않으므로 CI 에서 한 번 더 막는다.
# 사용: scripts/check-migrations.sh <비교 기준 커밋>   (기본: HEAD~1)
set -euo pipefail
base="${1:-HEAD~1}"
bad=$(git diff --name-status "$base" HEAD -- '*/db/migration/*' '*/db/messaging/*' \
  | awk '$1 ~ /^(M|D|R)/ && $NF ~ /\/V[^\/]*\.sql$/ { print }' || true)
if [[ -n "$bad" ]]; then
  echo "커밋된 마이그레이션은 바꿀 수 없습니다. 새 버전(V{n+1}__...sql)을 추가하세요:" >&2
  echo "$bad" >&2
  exit 1
fi
echo "migrations ok (기준: $base)"
