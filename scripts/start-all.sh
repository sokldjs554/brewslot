#!/usr/bin/env bash
# 인프라(PostgreSQL · Kafka · Redis) + 서비스 4개를 한 번에 띄운다.
#   ./scripts/start-all.sh      → 준비되면 접속 주소 출력
#   ./scripts/stop-all.sh       → 전부 종료
set -euo pipefail
cd "$(dirname "$0")/.."
RUN=.run; mkdir -p "$RUN"

echo "▶ 인프라 기동 (docker compose)"
docker compose up -d postgres kafka redis >/dev/null
until docker compose ps kafka --format '{{.Status}}' | grep -q healthy; do sleep 2; done

if ! ls services/order-service/build/libs/order-service-*[0-9].jar >/dev/null 2>&1; then
  echo "▶ 서비스 빌드 (최초 1회)"
  ./gradlew bootJar -q --console=plain
fi

declare -A PORT=([order]=8081 [payment]=8082 [loyalty]=8083 [settlement]=8084)
for s in order payment loyalty settlement; do
  if curl -sf "localhost:${PORT[$s]}/actuator/health" >/dev/null 2>&1; then continue; fi
  echo "▶ $s-service 기동"
  KAFKA_BOOTSTRAP=localhost:29092 nohup java -Xmx384m -XX:TieredStopAtLevel=1 \
    -jar services/$s-service/build/libs/$s-service-0.1.0.jar > "$RUN/$s.log" 2>&1 &
  echo $! > "$RUN/$s.pid"
done
for s in order payment loyalty settlement; do
  for _ in $(seq 1 120); do curl -sf "localhost:${PORT[$s]}/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "localhost:${PORT[$s]}/actuator/health" >/dev/null || { echo "✗ $s-service 기동 실패 — $RUN/$s.log 확인"; exit 1; }
done

cat <<MSG

✅ BrewSlot 준비 완료
   Swagger UI (주문)  : http://localhost:8081/swagger-ui.html
   Swagger UI (결제)  : http://localhost:8082/swagger-ui.html
   Swagger UI (포인트): http://localhost:8083/swagger-ui.html
   Swagger UI (정산)  : http://localhost:8084/swagger-ui.html
   전체 시나리오 데모 : ./scripts/demo.sh
MSG
