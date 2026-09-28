-- =====================================================================
-- 매장 변경(Store Transfer): 결제된 주문을 같은 브랜드의 다른 매장으로 옮긴다. docs/adr/0013-store-transfer.md
--
-- 새 매장 자리는 요청 시점에 이 transfer 의 id 를 예약 키로 먼저 잡아 두고(HELD),
-- 새 매장이 수락하는 트랜잭션에서 원래 매장 자리를 풀고 새 자리를 주문으로 넘긴다.
-- 거절 · 기한 초과 · 원래 매장이 먼저 제조 시작 → 새 자리만 풀고 원래 주문은 그대로 둔다.
-- =====================================================================
CREATE TABLE store_transfer
(
    id                 UUID PRIMARY KEY,
    order_id           UUID         NOT NULL REFERENCES orders (id),
    member_id          BIGINT       NOT NULL,
    from_store_id      BIGINT       NOT NULL,
    to_store_id        BIGINT       NOT NULL,
    previous_pickup_at TIMESTAMPTZ  NOT NULL,
    pickup_at          TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(20)  NOT NULL, -- AWAITING_STORE | COMPLETED | REFUSED | EXPIRED | ABORTED
    fail_reason        VARCHAR(40),
    new_lines          JSONB        NOT NULL, -- 새 매장 메뉴로 바꾼 주문 항목 (수락 시 order_line 을 교체)
    idempotency_key    VARCHAR(100) NOT NULL,
    request_hash       VARCHAR(64)  NOT NULL,
    requested_at       TIMESTAMPTZ  NOT NULL,
    deadline_at        TIMESTAMPTZ  NOT NULL,
    decided_at         TIMESTAMPTZ,
    CONSTRAINT uq_transfer_idempotency UNIQUE (member_id, idempotency_key),
    CONSTRAINT ck_transfer_other_store CHECK (from_store_id <> to_store_id),
    CONSTRAINT ck_transfer_decided CHECK ((status = 'AWAITING_STORE') = (decided_at IS NULL))
);
-- 한 주문에 진행 중인 변경 요청은 하나뿐 (동시 요청 두 개 중 하나는 DB 가 거절한다)
CREATE UNIQUE INDEX uq_transfer_one_pending ON store_transfer (order_id) WHERE status = 'AWAITING_STORE';
-- 매장 변경은 주문당 한 번 (정산 · 포인트 원장이 주문당 한 번의 귀속 이동을 전제로 한다)
CREATE UNIQUE INDEX uq_transfer_one_completed ON store_transfer (order_id) WHERE status = 'COMPLETED';
-- 새 매장 태블릿: 수락 대기 목록
CREATE INDEX idx_transfer_pending_by_store ON store_transfer (to_store_id, requested_at) WHERE status = 'AWAITING_STORE';
-- 기한 초과 스캐너
CREATE INDEX idx_transfer_deadline ON store_transfer (deadline_at) WHERE status = 'AWAITING_STORE';
CREATE INDEX idx_transfer_order ON store_transfer (order_id);
