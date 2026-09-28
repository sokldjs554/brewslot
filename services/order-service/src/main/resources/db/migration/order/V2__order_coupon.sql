-- 쿠폰(프로모션 이벤트) 적용. 할인은 주문 시 고객이 본 금액을 기록하고, 결제 Saga 1단계에서 loyalty-service 가 검증한다.
ALTER TABLE orders
    ADD COLUMN coupon_id     UUID,
    ADD COLUMN coupon_amount BIGINT NOT NULL DEFAULT 0 CHECK (coupon_amount >= 0);

ALTER TABLE orders DROP CONSTRAINT ck_point_le_total;
ALTER TABLE orders
    ADD CONSTRAINT ck_benefits_le_total CHECK (point_amount + coupon_amount <= total_amount),
    ADD CONSTRAINT ck_coupon_pair CHECK ((coupon_id IS NULL) = (coupon_amount = 0));

ALTER TABLE checkout_saga
    ADD COLUMN coupon_amount BIGINT NOT NULL DEFAULT 0;
