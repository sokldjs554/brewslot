package com.brewslot.loyalty

import com.brewslot.loyalty.domain.Consumption
import com.brewslot.loyalty.domain.Direction
import com.brewslot.loyalty.domain.Entry
import com.brewslot.loyalty.domain.Lot
import com.brewslot.loyalty.domain.LotAllocator
import com.brewslot.loyalty.domain.Posting
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class LotAllocatorTest {
    private val now = Instant.parse("2026-10-01T00:00:00Z")

    private fun day(d: Long) = now.plusSeconds(d * 86_400)

    @Test
    fun `만료가 가까운 lot 부터, 같으면 먼저 적립된 lot 부터 차감한다`() {
        val lots = listOf(Lot(1, 500, day(30)), Lot(2, 300, day(5)), Lot(3, 400, day(5)), Lot(4, 1000, day(100)))
        assertThat(LotAllocator.allocate(lots, 900, now)).containsExactly(Consumption(2, 300), Consumption(3, 400), Consumption(1, 200))
    }

    @Test
    fun `이미 만료된 lot 은 쓸 수 없고, 모자라면 null`() {
        val lots = listOf(Lot(1, 500, now), Lot(2, 300, day(1)))
        assertThat(LotAllocator.allocate(lots, 400, now)).isNull()
        assertThat(LotAllocator.allocate(lots, 300, now)).containsExactly(Consumption(2, 300))
    }

    @Test
    fun `차변과 대변이 맞지 않는 분개는 만들 수 없다`() {
        assertThatThrownBy { Posting.of(Entry("a", Direction.D, 100), Entry("b", Direction.C, 90)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
