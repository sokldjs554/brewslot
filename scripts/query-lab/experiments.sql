-- 각 실험: [BEFORE] 흔히 작성하는 형태 → [AFTER] 이 프로젝트가 채택한 형태
SET search_path = lab;
SET max_parallel_workers_per_gather = 0; -- 병렬 스캔으로 차이가 가려지지 않게 단일 워커로 비교

\echo '#### EXP1-BEFORE offset pagination (member_id index only), heavy member page 1000'
CREATE INDEX exp1_member ON member_order_view (member_id);
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT order_id, placed_at FROM member_order_view WHERE member_id = 424242
ORDER BY placed_at DESC, order_id DESC OFFSET 20000 LIMIT 20;
\echo '#### EXP1-AFTER keyset pagination + composite index'
CREATE INDEX exp1_member_placed ON member_order_view (member_id, placed_at DESC, order_id DESC);
SELECT 'exp1_member' AS index, pg_size_pretty(pg_relation_size('lab.exp1_member')) AS size;
DROP INDEX exp1_member;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT order_id, placed_at FROM member_order_view WHERE member_id = 424242
  AND (placed_at, order_id) < (SELECT placed_at, order_id FROM member_order_view WHERE member_id = 424242 ORDER BY placed_at DESC, order_id DESC OFFSET 19999 LIMIT 1)
ORDER BY placed_at DESC, order_id DESC LIMIT 20;

\echo '#### EXP2-BEFORE hold-expiry scan with (status) index'
CREATE INDEX exp2_status ON orders (status);
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= timestamptz '2026-01-01' ORDER BY hold_expires_at LIMIT 100;
\echo '#### EXP2-BEFORE-B hold-expiry scan with (status, hold_expires_at) full index'
CREATE INDEX exp2_status_exp ON orders (status, hold_expires_at);
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= timestamptz '2026-01-01' ORDER BY hold_expires_at LIMIT 100;
\echo '#### EXP2-AFTER partial index WHERE status = PENDING_PAYMENT'
SELECT 'exp2_status' AS index, pg_size_pretty(pg_relation_size('lab.exp2_status')) AS size;
SELECT 'exp2_status_exp' AS index, pg_size_pretty(pg_relation_size('lab.exp2_status_exp')) AS size;
DROP INDEX exp2_status; DROP INDEX exp2_status_exp;
CREATE INDEX exp2_partial ON orders (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND hold_expires_at <= timestamptz '2026-01-01' ORDER BY hold_expires_at LIMIT 100;

\echo '#### EXP3-BEFORE outbox poll without index on published_at'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 200;
\echo '#### EXP3-AFTER partial index (id) WHERE published_at IS NULL'
CREATE INDEX exp3_unpublished ON outbox_event (id) WHERE published_at IS NULL;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 200;

\echo '#### EXP4-BEFORE usable lots with (member_id, brand_id) index'
CREATE INDEX exp4_member_brand ON point_lot (member_id, brand_id);
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id, remaining, expires_at FROM point_lot
WHERE member_id = 777777 AND brand_id = 1 AND remaining > 0 AND expires_at > timestamptz '2026-01-01' ORDER BY expires_at, id;
\echo '#### EXP4-AFTER partial index (member_id, brand_id, expires_at, id) WHERE remaining > 0'
SELECT 'exp4_member_brand' AS index, pg_size_pretty(pg_relation_size('lab.exp4_member_brand')) AS size;
DROP INDEX exp4_member_brand;
CREATE INDEX exp4_usable ON point_lot (member_id, brand_id, expires_at, id) WHERE remaining > 0;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id, remaining, expires_at FROM point_lot
WHERE member_id = 777777 AND brand_id = 1 AND remaining > 0 AND expires_at > timestamptz '2026-01-01' ORDER BY expires_at, id;

\echo '#### EXP5-BEFORE open settlement entries with (business_date) index'
CREATE INDEX exp5_date ON settlement_entry (business_date);
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT DISTINCT store_id FROM settlement_entry WHERE statement_id IS NULL AND business_date <= date '2026-12-31';
\echo '#### EXP5-AFTER partial index WHERE statement_id IS NULL'
SELECT 'exp5_date' AS index, pg_size_pretty(pg_relation_size('lab.exp5_date')) AS size;
DROP INDEX exp5_date;
CREATE INDEX exp5_open ON settlement_entry (business_date, store_id) WHERE statement_id IS NULL;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT DISTINCT store_id FROM settlement_entry WHERE statement_id IS NULL AND business_date <= date '2026-12-31';

\echo '#### INDEX SIZES'
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) FROM pg_stat_user_indexes WHERE schemaname = 'lab' ORDER BY 1;
