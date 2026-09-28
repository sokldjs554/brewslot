-- =====================================================================
-- 고객이 실제로 쓰는 매장 변경 (ADR-0014)
--  1) 매장 위치: 원래 매장에서 걸어서 갈 수 있는 매장만 제안하고, 가까운 순으로 보여준다.
--  2) 자동 수락: 자리가 있으면 새 매장 직원 확인 없이 바로 옮긴다(출근 러시에 태블릿을 볼 틈이 없다).
--  3) 지연 감지: 매장이 밀리면 고객이 누르기 전에 먼저 알리고 옮길 매장 · 시각을 제안한다.
-- =====================================================================
ALTER TABLE store
    ADD COLUMN latitude              DOUBLE PRECISION,
    ADD COLUMN longitude             DOUBLE PRECISION,
    ADD COLUMN auto_accept_transfers BOOLEAN NOT NULL DEFAULT FALSE;

-- 주문당 한 번만 알린다(같은 지연으로 알림을 반복하지 않는다).
CREATE TABLE pickup_risk
(
    order_id            UUID PRIMARY KEY REFERENCES orders (id),
    store_id            BIGINT      NOT NULL,
    delay_minutes       INT         NOT NULL CHECK (delay_minutes > 0),
    suggested_store_id  BIGINT,
    suggested_pickup_at TIMESTAMPTZ,
    walk_minutes        INT,
    detected_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_risk_suggestion CHECK ((suggested_store_id IS NULL) = (suggested_pickup_at IS NULL))
);

-- 지연 감지 스캐너: 결제됐지만 아직 준비 완료가 아닌 극소수 주문만 본다.
CREATE INDEX idx_orders_in_production ON orders (store_id, promised_pickup_at) WHERE status IN ('PAID', 'PREPARING');
