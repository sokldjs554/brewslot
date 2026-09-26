-- 쿼리 튜닝 실험용 데이터 (운영 규모 근사). 실행: ./run.sh
\timing off
SET client_min_messages = warning;
DROP SCHEMA IF EXISTS lab CASCADE;
CREATE SCHEMA lab;
SET search_path = lab;

-- 1) 회원 주문내역 조회모델: 200만 건 (일반 회원 10만 명 × 평균 19건 + 헤비 유저 10만 건)
CREATE TABLE member_order_view (
    order_id UUID PRIMARY KEY, member_id BIGINT NOT NULL, status VARCHAR(30) NOT NULL,
    total_amount BIGINT NOT NULL, placed_at TIMESTAMPTZ NOT NULL, items JSONB NOT NULL
);
INSERT INTO member_order_view
SELECT gen_random_uuid(), (g % 100000), 'PICKED_UP', 4500, timestamptz '2025-01-01' + (g || ' seconds')::interval * 13, '[{"name":"아메리카노","quantity":1}]'
FROM generate_series(1, 1900000) g;
INSERT INTO member_order_view
SELECT gen_random_uuid(), 424242, 'PICKED_UP', 4500, timestamptz '2025-01-01' + (g || ' minutes')::interval, '[{"name":"아메리카노","quantity":1}]'
FROM generate_series(1, 100000) g;

-- 2) 주문 쓰기모델: 200만 건 중 결제 대기(PENDING_PAYMENT) 0.05%
CREATE TABLE orders (
    id UUID PRIMARY KEY, status VARCHAR(30) NOT NULL, hold_expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
INSERT INTO orders
SELECT gen_random_uuid(), CASE WHEN g % 2000 = 0 THEN 'PENDING_PAYMENT' ELSE (ARRAY['PICKED_UP','PICKED_UP','PICKED_UP','CANCELLED'])[1 + g % 4] END,
       timestamptz '2025-01-01' + (g || ' seconds')::interval * 15, now()
FROM generate_series(1, 2000000) g;

-- 3) Outbox: 발행 완료 200만 건 + 미발행 150건
CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY, topic VARCHAR(200) NOT NULL, envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ
);
INSERT INTO outbox_event (topic, envelope, published_at) SELECT 'order.events', '{"a":1}', now() FROM generate_series(1, 2000000);
INSERT INTO outbox_event (topic, envelope, published_at) SELECT 'order.events', '{"a":1}', NULL FROM generate_series(1, 150);

-- 4) 포인트 lot: 50만 회원, 오래된 회원일수록 다 쓴 lot 이 많다 (총 300만 lot, 잔여 있는 lot 은 약 8%)
CREATE TABLE point_lot (
    id BIGSERIAL PRIMARY KEY, member_id BIGINT NOT NULL, brand_id BIGINT NOT NULL,
    remaining BIGINT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
);
INSERT INTO point_lot (member_id, brand_id, remaining, expires_at)
SELECT g % 500000, 1 + (g % 3), CASE WHEN g % 12 = 0 THEN 100 ELSE 0 END, timestamptz '2026-01-01' + (g || ' seconds')::interval * 10
FROM generate_series(1, 3000000) g;
-- 오래 쓴 헤비 회원: lot 2만 개 중 잔여 30개
INSERT INTO point_lot (member_id, brand_id, remaining, expires_at)
SELECT 777777, 1, CASE WHEN g > 19970 THEN 100 ELSE 0 END, timestamptz '2026-01-01' + (g || ' hours')::interval
FROM generate_series(1, 20000) g;

-- 5) 정산 원천: 300만 건 중 미정산(statement_id IS NULL) 은 당일분 1만 건
CREATE TABLE settlement_entry (
    id BIGSERIAL PRIMARY KEY, store_id BIGINT NOT NULL, amount BIGINT NOT NULL,
    business_date DATE NOT NULL, statement_id BIGINT
);
INSERT INTO settlement_entry (store_id, amount, business_date, statement_id)
SELECT g % 3000, 4500, date '2025-09-01' + (g / 10000), CASE WHEN g > 2990000 THEN NULL ELSE g / 10000 END
FROM generate_series(1, 3000000) g;

ANALYZE;
