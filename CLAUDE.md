# BrewSlot — Claude Code 작업 가이드

픽업 약속 기반 카페 주문 플랫폼. Kotlin 2.2 / Spring Boot 3.5 / PostgreSQL 16 / Kafka / Redis.
설계 배경은 `docs/adr`, 요구사항은 `docs/prd`, API 계약은 `docs/api` 가 원천이다. 코드보다 먼저 읽는다.

## 명령

```bash
./gradlew build                                   # 전체: ktlint + 단위 + Testcontainers 통합 + 실행계획 회귀 + API 드리프트 + E2E
./gradlew :services:order-service:test --tests '*BrewSchedulerTest*'   # 좁혀서
./gradlew ktlintFormat                            # 포맷
docker compose up -d postgres kafka redis         # 로컬 인프라
python -m plan_doctor.cli 'services/*/build/query-plans/*.json'       # (tools/plan-doctor) 실행계획 진단
(cd services/dlt-console && pytest -q)            # DLT 콘솔 (FastAPI)
scripts/verify-n8n.sh                             # n8n 워크플로 실제 실행 검증
kubectl kustomize deploy/k8s/overlays/kind        # K8s 매니페스트 렌더링 (배포·스모크는 CI k8s.yml)
```
Docker 가 필요하다(Testcontainers). H2 로 바꾸지 않는다 — 핵심 로직이 PostgreSQL 고유 동작(잠금 순서, SKIP LOCKED, 부분 인덱스)에 기댄다.

## 구조

```
libs/common      Money(원 단위 Long), BusinessTime(KST 영업일), Uuid7
libs/web         RFC 9457 오류 응답
libs/messaging   이벤트 계약(contract/), Outbox, Inbox, DLT 에러 핸들러
libs/test-support Testcontainers 싱글톤, MutableClock, QueryPlan(실행계획 단언)
services/*       각 서비스: domain(순수) / application / infra / api
                 notification-service 는 WebFlux(Reactive) — 블로킹 호출 금지. dlt-console 은 Python(FastAPI)
e2e              5개 서비스를 한 JVM 에서 띄우는 전체 흐름 테스트
deploy/k8s       Kustomize. 서비스·환경변수를 추가하면 여기와 docker-compose.yml 을 함께 고친다
automation/n8n   운영 워크플로 JSON. 서비스 API 응답 형식을 바꾸면 mock_endpoints.py 도 맞춘다
```

## 반드시 지킬 규칙

1. **메시지는 `Outbox.publish()` 로만 발행한다.** `KafkaTemplate` 직접 호출 금지. 트랜잭션 밖에서 부르면 예외가 난다 — 그게 의도다.
2. **소비자는 멱등이어야 한다.** `Inbox.process()` 또는 자연 키 유니크 제약(`ON CONFLICT DO NOTHING`)을 쓴다.
3. **도메인 패키지(`..domain..`)는 Spring·Kafka·JDBC 를 모른다.** ArchUnit 이 검사한다. 시간은 `Instant now` 를 인자로 받는다(`Clock` 주입은 애플리케이션 계층).
4. **슬롯 행 잠금은 항상 `(station, slot_start)` 순서.** 새 잠금 경로를 만들면 `JdbcCapacityLedger` 를 거친다.
5. **적용된 Flyway 마이그레이션은 수정하지 않는다.** 새 버전 파일을 추가한다(`.claude/hooks/guard-migrations.sh` 가 막는다).
6. **금액은 `Money`(원 단위 Long).** 비율 계산은 `percentOf(bps, RoundingMode)` 로 반올림 정책을 명시한다. `Double` 금지.
7. **API 를 바꾸면 `docs/api/*.yaml` 을 먼저 고친다.** `OpenApiDriftTest` 가 설계와 구현의 경로·메서드·필수 헤더 불일치를 잡는다.
8. **핫 경로 쿼리를 추가하면 `QueryPlanRegressionTest` 에 실행계획 단언을 추가한다.** "인덱스를 탈 것" 은 리뷰 합의가 아니라 테스트다.
9. **외부 호출(PG 등)을 DB 트랜잭션 안에 두지 않는다.** 커넥션을 쥔 채 네트워크를 기다리지 않는다 (`PaymentService` 참고).
10. 이벤트 계약은 필드 **추가만** 한다. 의미가 바뀌면 새 eventType.

## 테스트 작성 관례

- 테스트 이름은 한국어 백틱 함수명으로 "무엇이 보장되는가" 를 쓴다. `@Nested` 클래스명은 영문 + `@DisplayName`(한글 클래스 파일명 금지).
- 통합 테스트는 날짜를 테스트마다 다르게 잡아(`clock.set(...)`) 공유 DB 에서도 서로 간섭하지 않게 한다.
- 동시성 버그는 "동시 N개 요청 → 불변식" 테스트로 고정한다(예: 슬롯 초과 예약 0, 포인트 잔액 = lot 합 = 원장 합).

## 커밋

Conventional Commits (`feat(order): ...`, `perf(loyalty): ...`, `docs(adr): ...`). 본문에 "왜" 를 쓴다.
