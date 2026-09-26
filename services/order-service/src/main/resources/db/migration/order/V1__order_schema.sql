-- =====================================================================
-- Catalog
-- =====================================================================
CREATE TABLE store
(
    id                  BIGINT PRIMARY KEY,
    brand_id            BIGINT       NOT NULL,
    name                VARCHAR(100) NOT NULL,
    open_time           TIME         NOT NULL,
    close_time          TIME         NOT NULL,
    slot_minutes        INT          NOT NULL DEFAULT 5,
    min_lead_minutes    INT          NOT NULL DEFAULT 5,
    max_advance_minutes INT          NOT NULL DEFAULT 180,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE store_station_capacity
(
    store_id       BIGINT      NOT NULL REFERENCES store (id),
    station        VARCHAR(20) NOT NULL,
    units_per_slot INT         NOT NULL CHECK (units_per_slot >= 0),
    PRIMARY KEY (store_id, station)
);

CREATE TABLE menu_item
(
    id                BIGINT PRIMARY KEY,
    store_id          BIGINT       NOT NULL REFERENCES store (id),
    name              VARCHAR(100) NOT NULL,
    price             BIGINT       NOT NULL CHECK (price > 0),
    station           VARCHAR(20)  NOT NULL,
    load_units        INT          NOT NULL CHECK (load_units > 0),
    freshness_minutes INT          NOT NULL CHECK (freshness_minutes > 0),
    available         BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_menu_item_store ON menu_item (store_id);

-- =====================================================================
-- Scheduling: 스테이션 × 슬롯 단위 제조 용량 원장
-- reserved 는 HELD + CONFIRMED 예약의 합. CHECK 제약이 초과 예약(oversell)의 최후 방어선이다.
-- =====================================================================
CREATE TABLE slot_capacity
(
    store_id   BIGINT      NOT NULL,
    station    VARCHAR(20) NOT NULL,
    slot_start TIMESTAMPTZ NOT NULL,
    capacity   INT         NOT NULL CHECK (capacity >= 0),
    reserved   INT         NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, station, slot_start),
    CONSTRAINT ck_slot_not_oversold CHECK (reserved >= 0 AND reserved <= capacity)
);

CREATE TABLE slot_reservation
(
    order_id   UUID PRIMARY KEY,
    store_id   BIGINT      NOT NULL,
    status     VARCHAR(20) NOT NULL, -- HELD | CONFIRMED | RELEASED
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE slot_reservation_line
(
    order_id   UUID        NOT NULL REFERENCES slot_reservation (order_id),
    store_id   BIGINT      NOT NULL,
    station    VARCHAR(20) NOT NULL,
    slot_start TIMESTAMPTZ NOT NULL,
    units      INT         NOT NULL CHECK (units > 0),
    PRIMARY KEY (order_id, station, slot_start)
);
-- 바리스타 제조 큐: "이 매장, 이 시간대에 무엇을 만들어야 하나"
CREATE INDEX idx_reservation_line_store_slot ON slot_reservation_line (store_id, slot_start);

-- =====================================================================
-- Ordering (write model)
-- =====================================================================
CREATE TABLE orders
(
    id                 UUID PRIMARY KEY, -- UUIDv7 (시간순 증가 → B-Tree 우측 삽입)
    member_id          BIGINT      NOT NULL,
    store_id           BIGINT      NOT NULL,
    brand_id           BIGINT      NOT NULL,
    status             VARCHAR(30) NOT NULL,
    promised_pickup_at TIMESTAMPTZ NOT NULL,
    total_amount       BIGINT      NOT NULL CHECK (total_amount > 0),
    point_amount       BIGINT      NOT NULL CHECK (point_amount >= 0),
    hold_expires_at    TIMESTAMPTZ NOT NULL,
    idempotency_key    VARCHAR(100) NOT NULL,
    request_hash       VARCHAR(64) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    paid_at            TIMESTAMPTZ,
    preparing_at       TIMESTAMPTZ,
    ready_at           TIMESTAMPTZ,
    picked_up_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    cancel_reason      VARCHAR(30),
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_orders_idempotency UNIQUE (member_id, idempotency_key),
    CONSTRAINT ck_point_le_total CHECK (point_amount <= total_amount)
);
-- 만료 스캐너는 PENDING_PAYMENT 인 극소수 행만 본다. 전체 주문 수와 무관하게 인덱스가 작다.
CREATE INDEX idx_orders_hold_expiry ON orders (hold_expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_line
(
    order_id          UUID         NOT NULL REFERENCES orders (id),
    line_no           INT          NOT NULL,
    menu_item_id      BIGINT       NOT NULL,
    name              VARCHAR(100) NOT NULL,
    unit_price        BIGINT       NOT NULL,
    quantity          INT          NOT NULL,
    station           VARCHAR(20)  NOT NULL,
    load_units        INT          NOT NULL,
    freshness_minutes INT          NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE checkout_saga
(
    order_id        UUID PRIMARY KEY REFERENCES orders (id),
    step            VARCHAR(30) NOT NULL,
    status          VARCHAR(30) NOT NULL,
    point_amount    BIGINT      NOT NULL,
    card_amount     BIGINT      NOT NULL,
    card_token      VARCHAR(100) NOT NULL,
    points_redeemed BOOLEAN     NOT NULL DEFAULT FALSE,
    started_at      TIMESTAMPTZ NOT NULL,
    deadline_at     TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_saga_deadline ON checkout_saga (deadline_at) WHERE status = 'RUNNING';

-- =====================================================================
-- Query side (CQRS read models, Kafka order.events 로부터 비동기 투영)
-- =====================================================================
CREATE TABLE member_order_view
(
    order_id           UUID PRIMARY KEY,
    member_id          BIGINT       NOT NULL,
    store_id           BIGINT       NOT NULL,
    store_name         VARCHAR(100) NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    total_amount       BIGINT       NOT NULL,
    promised_pickup_at TIMESTAMPTZ  NOT NULL,
    items              JSONB        NOT NULL,
    cart_signature     VARCHAR(64)  NOT NULL,
    placed_at          TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);
-- 커서 기반 주문내역: WHERE member_id = ? AND (placed_at, order_id) < (?, ?) ORDER BY placed_at DESC, order_id DESC
-- 이 인덱스 하나로 필터·정렬·LIMIT 를 모두 인덱스 순서대로 처리한다(Sort 노드 없음). docs/performance/query-plans.md #2
CREATE INDEX idx_mov_member_placed ON member_order_view (member_id, placed_at DESC, order_id DESC);

CREATE TABLE store_daily_stats
(
    store_id               BIGINT NOT NULL,
    business_date          DATE   NOT NULL,
    paid_orders            INT    NOT NULL DEFAULT 0,
    gross_amount           BIGINT NOT NULL DEFAULT 0,
    cancelled_orders       INT    NOT NULL DEFAULT 0,
    ready_orders           INT    NOT NULL DEFAULT 0,
    on_time_orders         INT    NOT NULL DEFAULT 0,
    total_lateness_seconds BIGINT NOT NULL DEFAULT 0,
    picked_up_orders       INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, business_date)
);
