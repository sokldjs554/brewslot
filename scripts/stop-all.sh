#!/usr/bin/env bash
cd "$(dirname "$0")/.."
for f in .run/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done
docker compose down -v >/dev/null 2>&1
echo "stopped"
