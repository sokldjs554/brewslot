#!/usr/bin/env bash
# PreToolUse: 이미 커밋된(= 어딘가에 적용됐을 수 있는) Flyway 마이그레이션 수정을 차단한다.
# Flyway 는 적용된 파일의 체크섬이 바뀌면 기동을 거부한다. 수정 대신 새 버전(V{n+1}__...) 을 추가해야 한다.
set -euo pipefail
file=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))')
[[ -z "$file" ]] && exit 0
case "$file" in
  */db/migration/*/V*.sql|*/db/messaging/V*.sql)
    rel=${file#"$CLAUDE_PROJECT_DIR"/}
    if git -C "$CLAUDE_PROJECT_DIR" cat-file -e "HEAD:$rel" 2>/dev/null; then
      echo "차단: '$rel' 는 이미 커밋된 마이그레이션입니다. 수정하지 말고 새 버전 파일을 추가하세요 (CLAUDE.md 규칙 5)." >&2
      exit 2
    fi
    ;;
esac
exit 0
