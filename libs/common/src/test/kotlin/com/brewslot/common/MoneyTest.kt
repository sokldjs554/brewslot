package com.brewslot.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.RoundingMode

class MoneyTest {
    @Test
    fun `basis point 비율은 호출자가 지정한 반올림 정책을 따른다`() {
        val amount = Money(10_050)

        assertThat(amount.percentOf(220, RoundingMode.HALF_UP)).isEqualTo(Money(221)) // 221.1
        assertThat(amount.percentOf(250, RoundingMode.HALF_UP)).isEqualTo(Money(251)) // 251.25
        assertThat(amount.percentOf(250, RoundingMode.DOWN)).isEqualTo(Money(251))
        assertThat(Money(10_060).percentOf(250, RoundingMode.HALF_UP)).isEqualTo(Money(252)) // 251.5
        assertThat(Money(10_060).percentOf(250, RoundingMode.DOWN)).isEqualTo(Money(251))
    }

    @Test
    fun `오버플로는 조용히 넘어가지 않는다`() {
        assertThatThrownBy { Money(Long.MAX_VALUE) + Money(1) }.isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `합계`() {
        assertThat(listOf(Money(1_000), Money(2_500), Money(-500)).sum()).isEqualTo(Money(3_000))
    }
}
