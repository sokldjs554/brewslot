-- 결제 (우리 시스템의 기록)
CREATE TABLE payment
(
    id                UUID PRIMARY KEY,
    order_id          UUID        NOT NULL UNIQUE, -- 주문당 결제 1건: ChargePayment 중복 수신의 최종 방어선
    member_id         BIGINT      NOT NULL,
    store_id          BIGINT      NOT NULL,
    brand_id          BIGINT      NOT NULL,
    amount            BIGINT      NOT NULL CHECK (amount > 0),
    card_token        VARCHAR(100) NOT NULL,
    status            VARCHAR(20) NOT NULL, -- REQUESTED | CAPTURED | FAILED | UNKNOWN | REFUNDED
    pg_transaction_id VARCHAR(64),
    pg_cancel_id      VARCHAR(64),
    failure_reason    VARCHAR(100),
    refund_requested  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL,
    captured_at       TIMESTAMPTZ,
    refunded_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL
);
-- 결과 미확정 결제 복구 스캐너용 (대부분의 결제는 CAPTURED 이므로 부분 인덱스가 매우 작다)
CREATE INDEX idx_payment_unresolved ON payment (updated_at) WHERE status IN ('REQUESTED', 'UNKNOWN');

-- =====================================================================
-- Fake PG: 외부 PG 사 시스템을 흉내 낸다. 실제 운영에서는 존재하지 않는 테이블.
-- (결과를 알 수 없는 타임아웃, PG 정산 파일 등 "외부 시스템과의 불일치" 시나리오를 재현하기 위함)
-- =====================================================================
CREATE TABLE fake_pg_transaction
(
    id           VARCHAR(64) PRIMARY KEY,
    order_id     UUID        NOT NULL,
    kind         VARCHAR(10) NOT NULL, -- APPROVE | CANCEL
    amount       BIGINT      NOT NULL,
    fee          BIGINT      NOT NULL,
    transacted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (order_id, kind)
);
CREATE INDEX idx_fake_pg_tx_time ON fake_pg_transaction (transacted_at);
