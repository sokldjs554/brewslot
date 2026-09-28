package com.brewslot.loyalty.domain

/**
 * 계정과목. 문자열 키로 표현해 계정 테이블 없이도 분개를 남길 수 있게 했다.
 *
 * | 계정                        | 성격 | 의미                                        |
 * |-----------------------------|------|---------------------------------------------|
 * | member:{m}:brand:{b}        | 부채 | 회원이 가진 브랜드 포인트 (브랜드가 갚아야 할 빚) |
 * | brand:{b}:funding           | 비용 | 브랜드가 적립해 준 포인트 비용                   |
 * | store:{s}:clearing          | 채권 | 포인트로 결제된 매출 → 정산 때 매장에 지급         |
 * | brand:{b}:breakage          | 수익 | 소멸된 포인트 (브랜드 부채 소멸)                  |
 * | brand:{b}:promotion         | 비용 | 쿠폰 할인액 (브랜드 부담, 매장은 정가로 정산)       |
 */
object Accounts {
    fun memberWallet(memberId: Long, brandId: Long) = "member:$memberId:brand:$brandId"

    fun brandFunding(brandId: Long) = "brand:$brandId:funding"

    fun storeClearing(storeId: Long) = "store:$storeId:clearing"

    fun brandBreakage(brandId: Long) = "brand:$brandId:breakage"

    fun brandPromotion(brandId: Long) = "brand:$brandId:promotion"
}

enum class Direction { D, C }

data class Entry(val account: String, val direction: Direction, val amount: Long) {
    init {
        require(amount > 0) { "entry amount must be positive" }
    }
}

/** 차변 합계 = 대변 합계 를 만족하는 분개 묶음. 애플리케이션 레벨 검사(DB 트리거가 한 번 더 검사한다). */
class Posting private constructor(val entries: List<Entry>) {
    companion object {
        fun transfer(debit: String, credit: String, amount: Long) =
            of(Entry(debit, Direction.D, amount), Entry(credit, Direction.C, amount))

        fun of(vararg entries: Entry): Posting {
            val debit = entries.filter { it.direction == Direction.D }.sumOf { it.amount }
            val credit = entries.filter { it.direction == Direction.C }.sumOf { it.amount }
            require(debit == credit) { "unbalanced posting: debit=$debit credit=$credit" }
            return Posting(entries.toList())
        }
    }
}

enum class TxType { EARN, GRANT, REDEEM, REVERSE_REDEEM, EXPIRE, COUPON_REDEEM, REVERSE_COUPON }
