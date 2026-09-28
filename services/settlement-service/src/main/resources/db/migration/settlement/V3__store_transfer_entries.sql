-- 매장 변경(ADR-0013): 결제 금액은 그대로이고 "어느 매장의 매출인가" 만 바뀐다.
-- 원천 행은 TRANSFER_OUT(원래 매장, -총액, -카드 수수료) / TRANSFER_IN(새 매장, +총액, +카드 수수료) 한 쌍.
ALTER TABLE settlement_statement
    ADD COLUMN transfers_in  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN transfers_out BIGINT NOT NULL DEFAULT 0;
