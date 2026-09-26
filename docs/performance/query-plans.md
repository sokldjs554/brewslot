# 쿼리 실행계획 분석 & 튜닝 기록

> 모든 수치는 `scripts/query-lab/run.sh` 로 재현할 수 있습니다 (PostgreSQL 16, 단일 워커, 캐시 워밍 후 2회차 측정).
> 원본 출력: [`query-lab-output.txt`](query-lab-output.txt)
> 튜닝 결과가 나중에 퇴화하지 않도록 [`QueryPlanRegressionTest`](../../services/order-service/src/test/kotlin/com/brewslot/order/performance/QueryPlanRegressionTest.kt) 가 CI 에서 **실행계획 자체를 검증**합니다.

## 요약

| # | 쿼리 (호출 빈도) | 데이터 규모 | Before | After | 개선 | 핵심 기법 |
|---|---|---|---:|---:|---:|---|
| 1 | 헤비 유저 주문내역 1000페이지째 (조회 API) | 200만 행 / 회원 1명 10만 건 | 51.5 ms | **3.5 ms** (커서 값 조회 포함) / 0.07 ms (앱 실제 쿼리) | 15× ~ 700× | keyset pagination + `(member_id, placed_at DESC, order_id DESC)` Index Only Scan |
| 2 | 결제 대기 주문 만료 스캔 (5초마다) | 주문 200만 건 중 0.05% 대상 | 2.1 ms · 인덱스 13 MB | **0.22 ms · 인덱스 40 kB** | 10× · 크기 1/330 | 부분 인덱스 `WHERE status = 'PENDING_PAYMENT'` |
| 3 | Outbox 미발행 폴링 (200ms마다) | 발행 완료 200만 + 미발행 150 | 140.9 ms (Seq Scan 2M) | **0.08 ms** · 인덱스 16 kB | 1,800× | 부분 인덱스 `WHERE published_at IS NULL` |
| 4 | 포인트 사용 가능 lot (결제마다) | lot 300만 / 헤비 회원 2만 lot 중 잔여 30 | 1.79 ms · 인덱스 71 MB | **0.085 ms · 인덱스 12 MB** | 21× · 크기 1/6 | 부분 인덱스 `WHERE remaining > 0` + 만료순 키 |
| 5 | 정산 마감 대상 매장 (일 1회 배치) | 정산 원천 300만 중 미정산 1만 | 161.2 ms (Seq Scan) | **4.3 ms** · 인덱스 192 kB | 37× | 부분 인덱스 `WHERE statement_id IS NULL` + Index Only Scan |

공통 패턴: **"전체 중 극소수만 조회 대상인 상태 기반 쿼리"** 는 상태 컬럼 인덱스가 아니라 **부분 인덱스**가 답이다.
인덱스가 대상 행 수에만 비례하므로 테이블이 커져도 폴링 비용과 쓰기 증폭이 늘지 않는다.

---

## 1. 주문내역: OFFSET → keyset pagination

```sql
-- Before: member_id 단일 인덱스 + OFFSET
SELECT order_id, placed_at FROM member_order_view WHERE member_id = 424242
ORDER BY placed_at DESC, order_id DESC OFFSET 20000 LIMIT 20;
```
```
Limit
  -> Sort  (actual rows=20020)  Sort Method: top-N heapsort  Memory: 4352kB
       -> Bitmap Heap Scan on member_order_view (actual rows=100000)      ← 회원의 10만 건을 전부 읽고
            -> Bitmap Index Scan on exp1_member (actual rows=100000)       ← 정렬한 뒤 20,000건을 버린다
Execution Time: 51.501 ms
```
- 페이지가 깊어질수록 O(offset) 로 느려지고, 조회 도중 새 주문이 들어오면 페이지 경계가 밀려 **중복·누락**이 생긴다.

```sql
-- After: 복합 인덱스 + 커서 (placed_at, order_id) 행 비교
SELECT order_id, placed_at FROM member_order_view
WHERE member_id = :memberId AND (placed_at, order_id) < (:cursorPlacedAt, :cursorOrderId)
ORDER BY placed_at DESC, order_id DESC LIMIT 21;
```
```
Limit
  -> Index Only Scan using idx_mov_member_placed (actual rows=21)          ← 21행만 읽고 끝. Sort 노드 없음
```
- 실험 쿼리(3.5 ms)는 "20,000번째 행의 커서 값" 을 서브쿼리로 구하는 비용이 포함된 수치이고,
  실제 API 는 클라이언트가 받은 커서를 그대로 쓰므로 회귀 테스트 기준 **0.07 ms** 다.
- `order_id` 를 정렬 키에 포함한 이유: `placed_at` 이 같은 주문이 있어도 순서가 결정적이어야 커서가 안전하다.
- `LIMIT 21` = 요청 20 + 1: 다음 페이지 존재 여부를 COUNT 쿼리 없이 판단한다.

> **관찰:** 주문이 20건뿐인 일반 회원은 플래너가 `Bitmap Scan + Sort(20행)` 를 고른다. 이는 합리적인 선택이라
> 회귀 테스트는 "헤비 유저(3만 건)에서 Sort 노드가 없어야 한다" 를 검증한다. 데이터 분포에 따라 계획이 달라진다는 점을
> 테스트 설계에 반영한 것이다.

## 2. 결제 대기 주문 만료 스캔: 부분 인덱스

| 인덱스 | 실행 시간 | 인덱스 크기 | 비고 |
|---|---:|---:|---|
| `(status)` | 2.11 ms | 13 MB | PENDING 1000건을 모두 읽고 `hold_expires_at` 정렬 |
| `(status, hold_expires_at)` | 0.28 ms | **77 MB** | 속도는 충분하지만 모든 주문(200만)이 인덱스에 들어감 |
| `(hold_expires_at) WHERE status = 'PENDING_PAYMENT'` | **0.22 ms** | **40 kB** | 결제 대기 주문만 인덱싱 |

복합 인덱스도 빠르지만, 주문 상태가 바뀔 때마다(하루 수십만 번) 77 MB 인덱스를 갱신해야 한다.
부분 인덱스는 **PENDING 으로 들어올 때 한 번, 나갈 때 한 번**만 갱신된다.

## 3. Outbox 폴링: 가장 극적인 차이

```
Before: Seq Scan on outbox_event  (Rows Removed by Filter: 2,000,000)  Buffers: read=22633   140.9 ms
After : Index Only Scan using exp3_unpublished (rows=150)             Buffers: hit=2 read=1    0.08 ms
```
200ms 마다 도는 쿼리가 140ms 라면 릴레이가 사실상 DB 를 계속 풀스캔하는 셈이다.
발행 완료 행은 보관 기간(7일) 후 삭제하지만, 삭제 전까지 쌓이는 양과 무관하게 폴링 비용이 일정해야 한다.

## 4. 포인트 lot: "다 쓴 lot" 을 인덱스에서 제외

오래 쓴 회원일수록 잔여 0인 lot 이 쌓인다. `(member_id, brand_id)` 인덱스는 2만 개 lot 을 모두 읽은 뒤 `remaining > 0` 으로 걸러낸다.
`WHERE remaining > 0` 부분 인덱스는 **30개만** 읽는다. 결제 Saga 의 첫 단계(포인트 차감)가 이 쿼리이므로 결제 지연에 직접 영향을 준다.

## 5. 정산 마감: 미정산 행만

`business_date <= :date` 조건은 과거 전체와 일치하므로 날짜 인덱스를 써도 플래너가 Seq Scan 을 고른다(161 ms).
`WHERE statement_id IS NULL` 부분 인덱스는 "아직 정산서에 묶이지 않은 행" 만 담아 **Index Only Scan 4.3 ms**.

---

## 실행계획 회귀 테스트 & plan-doctor

1. `QueryPlanRegressionTest` 가 운영 규모 데이터를 넣고 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 을 실행해
   **사용 인덱스 / Sort 노드 유무 / Seq Scan 대상 테이블** 을 단언한다.
2. 실행계획 JSON 은 `build/query-plans/*.json` 으로 남고, CI 가 아티팩트로 업로드한다.
3. [`tools/plan-doctor`](../../tools/plan-doctor) 가 이 JSON 을 규칙 기반으로 진단하고(`--fail-on high` 로 CI 게이트),
   `--ai` 옵션이면 스키마(Flyway SQL)와 함께 Claude 에게 보내 "왜 이 계획인지 / 어떤 인덱스가 좋은지 / 그 대가는" 리뷰를 받는다.
