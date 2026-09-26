#!/usr/bin/env bash
# PostToolUse: Kotlin 파일을 고치면 해당 모듈만 ktlintFormat. 실패해도 작업을 막지 않는다(리포트는 CI 가 잡는다).
set -uo pipefail
file=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))')
[[ "$file" == *.kt || "$file" == *.kts ]] || exit 0
rel=${file#"$CLAUDE_PROJECT_DIR"/}
module=$(echo "$rel" | awk -F/ '($1=="services"||$1=="libs"){print ":"$1":"$2; exit} $1=="e2e"{print ":e2e"}')
[[ -n "$module" ]] || exit 0
cd "$CLAUDE_PROJECT_DIR" && ./gradlew -q "$module:ktlintFormat" >/dev/null 2>&1 || true
exit 0
