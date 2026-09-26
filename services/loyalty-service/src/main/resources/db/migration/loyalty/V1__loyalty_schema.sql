-- =====================================================================
-- 복식부기 포인트 원장
--
-- 모든 포인트 이동은 하나의 ledger_transaction 과 2개 이상의 ledger_entry(차변/대변)로 기록되며,
-- 거래마다 차변 합계 = 대변 합계 가 DB 트리거로 강제된다(커밋 시점 검사).
-- 잔액은 "저장된 값" 이 아니라 원장에서 "유도되는 값" 이고, point_wallet.balance 는 조회용 캐시다.
-- =====================================================================
CREATE TABLE brand_point_policy
(
    brand_id      BIGINT PRIMARY KEY,
    earn_rate_bps INT NOT NULL CHECK (earn_rate_bps BETWEEN 0 AND 10000),
    expiry_days   INT NOT NULL CHECK (expiry_days > 0)
);

CREATE TABLE ledger_transaction
(
    id         BIGSERIAL PRIMARY KEY,
    tx_type    VARCHAR(20) NOT NULL, -- EARN | GRANT | REDEEM | REVERSE_REDEEM | EXPIRE
    order_id   UUID,
    member_id  BIGINT      NOT NULL,
    brand_id   BIGINT      NOT NULL,
    store_id   BIGINT,
    amount     BIGINT      NOT NULL CHECK (amount > 0),
    memo       VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL
);
-- 주문 단위 멱등성: 같은 주문으로 두 번 적립/사용/취소되지 않는다.
CREATE UNIQUE INDEX uq_ledger_tx_order_type ON ledger_transaction (order_id, tx_type) WHERE order_id IS NOT NULL;
-- 회원·브랜드별 포인트 내역 (커서 페이지네이션)
CREATE INDEX idx_ledger_tx_member_brand ON ledger_transaction (member_id, brand_id, id DESC);

CREATE TABLE ledger_entry
(
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT       NOT NULL REFERENCES ledger_transaction (id),
    account        VARCHAR(100) NOT NULL, -- 예) member:42:brand:1, brand:1:funding, store:101:clearing, brand:1:breakage
    direction      CHAR(1)      NOT NULL CHECK (direction IN ('D', 'C')),
    amount         BIGINT       NOT NULL CHECK (amount > 0)
);
CREATE INDEX idx_ledger_entry_tx ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_account ON ledger_entry (account);

CREATE FUNCTION assert_ledger_balanced() RETURNS trigger AS
$$
DECLARE
    diff BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'D' THEN amount ELSE -amount END), 0)
    INTO diff
    FROM ledger_entry
    WHERE transaction_id = NEW.transaction_id;
    IF diff <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced (debit - credit = %)', NEW.transaction_id, diff;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- DEFERRABLE INITIALLY DEFERRED: 한 거래의 분개를 여러 INSERT 로 나눠 쓰더라도 커밋 시점에 한 번에 검사한다.
CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION assert_ledger_balanced();

-- 소멸 단위(lot). 적립 건마다 만료일이 다르므로 사용 시 "만료가 가까운 것부터" 차감한다(FIFO by expiry).
CREATE TABLE point_lot
(
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT      NOT NULL,
    brand_id        BIGINT      NOT NULL,
    source_tx_id    BIGINT      NOT NULL REFERENCES ledger_transaction (id),
    original_amount BIGINT      NOT NULL CHECK (original_amount > 0),
    remaining       BIGINT      NOT NULL CHECK (remaining >= 0),
    earned_at       TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lot_remaining CHECK (remaining <= original_amount)
);
-- 사용 가능 lot 만 만료순으로: 다 쓴 lot(remaining = 0)은 인덱스에서 빠지므로 오래된 회원도 비용이 일정하다.
CREATE INDEX idx_lot_usable ON point_lot (member_id, brand_id, expires_at, id) WHERE remaining > 0;
-- 소멸 배치: 만료됐는데 남은 포인트가 있는 lot
CREATE INDEX idx_lot_expiring ON point_lot (expires_at) WHERE remaining > 0;

-- 사용 시 어느 lot 에서 얼마를 뺐는지. 사용 취소 시 정확히 같은 lot 으로 되돌린다.
CREATE TABLE lot_consumption
(
    transaction_id BIGINT NOT NULL REFERENCES ledger_transaction (id),
    lot_id         BIGINT NOT NULL REFERENCES point_lot (id),
    amount         BIGINT NOT NULL CHECK (amount > 0),
    PRIMARY KEY (transaction_id, lot_id)
);

-- 조회·동시성 제어용 잔액 행. (member, brand) 단위로 FOR UPDATE 해 같은 지갑의 사용을 직렬화한다.
CREATE TABLE point_wallet
(
    member_id  BIGINT      NOT NULL,
    brand_id   BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL CHECK (balance >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (member_id, brand_id)
);
