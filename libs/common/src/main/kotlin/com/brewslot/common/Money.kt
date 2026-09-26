package com.brewslot.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 원화 금액. 원 단위 미만은 존재하지 않으므로 Long 으로 표현한다.
 *
 * 수수료 계산처럼 비율이 개입되는 경우에만 [percentOf] 에서 반올림 정책을 명시적으로 받는다.
 * (정산 금액의 1원 차이는 대사(reconciliation) 불일치로 직결되기 때문에 암묵적 반올림을 허용하지 않는다.)
 */
@JvmInline
value class Money(val won: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(won, other.won))

    operator fun minus(other: Money) = Money(Math.subtractExact(won, other.won))

    operator fun times(quantity: Int) = Money(Math.multiplyExact(won, quantity.toLong()))

    operator fun unaryMinus() = Money(Math.negateExact(won))

    override fun compareTo(other: Money) = won.compareTo(other.won)

    val isZero: Boolean get() = won == 0L
    val isPositive: Boolean get() = won > 0L
    val isNegative: Boolean get() = won < 0L

    /** basis point(1/10000) 단위 비율을 적용한다. 예: 220bps = 2.20% */
    fun percentOf(basisPoints: Int, rounding: RoundingMode): Money =
        Money(
            BigDecimal.valueOf(won)
                .multiply(BigDecimal.valueOf(basisPoints.toLong()))
                .divide(BigDecimal.valueOf(10_000L), 0, rounding)
                .longValueExact(),
        )

    fun min(other: Money): Money = if (this <= other) this else other

    override fun toString(): String = "${won}원"

    companion object {
        val ZERO = Money(0)

        fun won(amount: Long) = Money(amount)

        fun sum(values: Iterable<Money>): Money = values.fold(ZERO) { acc, m -> acc + m }
    }
}

fun Iterable<Money>.sum(): Money = Money.sum(this)
