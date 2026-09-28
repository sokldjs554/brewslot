-- =====================================================================
-- 프로모션(이벤트) 쿠폰
--
-- 캠페인: 브랜드가 여는 이벤트. 선착순 발급 한도(issue_limit)를 넘겨 발급되지 않도록 조건부 UPDATE 로 센다.
-- 회원 쿠폰: 캠페인당 회원 1장. 결제 Saga 에서 포인트와 같은 로컬 트랜잭션으로 사용(USED)되고,
--           보상 시 유효기간이 남아 있으면 ISSUED 로, 지났으면 EXPIRED 로 되돌아간다.
-- 할인 비용은 브랜드가 부담한다: 차) brand:{b}:promotion / 대) store:{s}:clearing — 매장은 정가 기준으로 정산받는다.
-- =====================================================================
CREATE TABLE coupon_campaign
(
    id               BIGSERIAL PRIMARY KEY,
    brand_id         BIGINT       NOT NULL,
    name             VARCHAR(100) NOT NULL,
    discount_amount  BIGINT       NOT NULL CHECK (discount_amount > 0),
    min_order_amount BIGINT       NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    issue_limit      INT          NOT NULL CHECK (issue_limit > 0),
    issued_count     INT          NOT NULL DEFAULT 0,
    valid_days       INT          NOT NULL CHECK (valid_days > 0),
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_campaign_issued CHECK (issued_count BETWEEN 0 AND issue_limit),
    CONSTRAINT ck_campaign_period CHECK (starts_at < ends_at)
);

CREATE TABLE member_coupon
(
    id               UUID PRIMARY KEY,
    campaign_id      BIGINT      NOT NULL REFERENCES coupon_campaign (id),
    member_id        BIGINT      NOT NULL,
    brand_id         BIGINT      NOT NULL,
    discount_amount  BIGINT      NOT NULL CHECK (discount_amount > 0),
    min_order_amount BIGINT      NOT NULL,
    status           VARCHAR(10) NOT NULL, -- ISSUED | USED | EXPIRED
    issued_at        TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    used_order_id    UUID,
    used_at          TIMESTAMPTZ,
    CONSTRAINT uq_coupon_campaign_member UNIQUE (campaign_id, member_id),
    CONSTRAINT ck_coupon_used CHECK ((status = 'USED') = (used_order_id IS NOT NULL))
);
-- 결제 화면의 "쓸 수 있는 쿠폰": 회원의 ISSUED 쿠폰만 만료 임박순으로
CREATE INDEX idx_coupon_usable ON member_coupon (member_id, brand_id, expires_at) WHERE status = 'ISSUED';
-- 한 주문에는 쿠폰 1장
CREATE UNIQUE INDEX uq_coupon_order ON member_coupon (used_order_id) WHERE used_order_id IS NOT NULL;
