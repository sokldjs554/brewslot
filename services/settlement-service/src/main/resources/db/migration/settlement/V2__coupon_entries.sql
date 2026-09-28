-- 쿠폰 할인분도 매장 매출이다(브랜드가 부담해 플랫폼이 매장에 지급). 원천 행은 COUPON_SALE / COUPON_REVERSAL.
ALTER TABLE settlement_statement
    ADD COLUMN coupon_sales     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN coupon_reversals BIGINT NOT NULL DEFAULT 0;
