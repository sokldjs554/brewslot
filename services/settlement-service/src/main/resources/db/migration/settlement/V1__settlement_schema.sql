-- 정산 원천 기록: 돈이 움직인 "사실" 하나당 한 행. 이벤트 ID 로 중복 적재를 막는다.
CREATE TABLE settlement_entry
(
    id                BIGSERIAL PRIMARY KEY,
    source_event_id   UUID        NOT NULL UNIQUE,
    store_id          BIGINT      NOT NULL,
    brand_id          BIGINT      NOT NULL,
    order_id          UUID        NOT NULL,
    kind              VARCHAR(20) NOT NULL, -- CARD_SALE | CARD_REFUND | POINT_SALE | POINT_REVERSAL
    amount            BIGINT      NOT NULL, -- 매출 증가는 +, 감소는 -
    pg_fee            BIGINT      NOT NULL DEFAULT 0,
    pg_transaction_id VARCHAR(64),
    occurred_at       TIMESTAMPTZ NOT NULL,
    business_date     DATE        NOT NULL, -- 한국 시간 기준 거래일
    statement_id      BIGINT
);
-- 마감 배치: "아직 정산서에 묶이지 않은 행" 만 본다. 정산이 끝난 과거 행이 쌓여도 인덱스는 당일 규모로 유지된다.
CREATE INDEX idx_entry_open ON settlement_entry (business_date, store_id) WHERE statement_id IS NULL;
-- 대사: 거래일의 카드 거래를 PG 파일과 맞춘다.
CREATE INDEX idx_entry_card_by_date ON settlement_entry (business_date) WHERE kind IN ('CARD_SALE', 'CARD_REFUND');

CREATE TABLE settlement_statement
(
    id                 BIGSERIAL PRIMARY KEY,
    store_id           BIGINT      NOT NULL,
    settlement_date    DATE        NOT NULL,
    card_sales         BIGINT      NOT NULL,
    card_refunds       BIGINT      NOT NULL,
    point_sales        BIGINT      NOT NULL,
    point_reversals    BIGINT      NOT NULL,
    net_sales          BIGINT      NOT NULL,
    pg_fee             BIGINT      NOT NULL,
    platform_fee       BIGINT      NOT NULL,
    payout             BIGINT      NOT NULL,
    entry_count        INT         NOT NULL,
    carried_over_count INT         NOT NULL, -- 이전 거래일에 발생했지만 마감 이후 도착해 이번 정산에 포함된 건
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_statement_store_date UNIQUE (store_id, settlement_date),
    CONSTRAINT ck_payout CHECK (payout = net_sales - pg_fee - platform_fee)
);
-- 점주 정산 내역 조회 (store_id, 기간)
CREATE INDEX idx_statement_store_date ON settlement_statement (store_id, settlement_date DESC);

CREATE TABLE reconciliation_run
(
    id            BIGSERIAL PRIMARY KEY,
    business_date DATE        NOT NULL,
    pg_count      INT         NOT NULL,
    ledger_count  INT         NOT NULL,
    matched_count INT         NOT NULL,
    issue_count   INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE reconciliation_issue
(
    id                BIGSERIAL PRIMARY KEY,
    run_id            BIGINT      NOT NULL REFERENCES reconciliation_run (id),
    issue_type        VARCHAR(30) NOT NULL, -- MISSING_IN_LEDGER | MISSING_IN_PG | AMOUNT_MISMATCH | FEE_MISMATCH
    pg_transaction_id VARCHAR(64) NOT NULL,
    order_id          UUID,
    ledger_amount     BIGINT,
    pg_amount         BIGINT,
    ledger_fee        BIGINT,
    pg_fee            BIGINT
);
CREATE INDEX idx_issue_run ON reconciliation_issue (run_id);
