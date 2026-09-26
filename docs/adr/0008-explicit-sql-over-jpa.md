# ADR-0008. JPA 대신 JdbcClient + 명시적 SQL

- 상태: 채택

## 맥락

이 시스템의 핵심 로직은 PostgreSQL 고유 기능에 기대고 있다: `FOR UPDATE` 잠금 순서, `SKIP LOCKED`, advisory lock,
부분 인덱스, `ON CONFLICT`, 조건부 UPDATE, 행 비교 `(a, b) < (?, ?)`, 제약 트리거. 그리고 실행계획을 테스트로 검증한다.

## 결정

- 영속성은 Spring `JdbcClient` 로 SQL 을 직접 작성한다. 실행되는 SQL 이 코드에 그대로 보이므로 실행계획 분석·리뷰가 쉽다.
- 도메인 모델(`Order`, `CheckoutSaga`, `BrewScheduler`)은 JPA 애노테이션 없는 순수 Kotlin 이며, ArchUnit 이
  `..domain..` 패키지의 Spring/Kafka/JDBC 의존을 금지한다.
- 낙관적 잠금은 `version` 조건부 UPDATE 로 직접 구현한다.

## 대가

- 매핑 코드가 늘어난다. 테이블 수가 적고 쿼리 대부분이 성능 민감 경로라 감수한다.
- 팀 표준이 JPA 라면: 쓰기 모델의 단순 CRUD 는 JPA, 잠금·배치·조회 모델은 네이티브 SQL 로 나누는 절충이 현실적이다.
