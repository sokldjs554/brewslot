package com.brewslot.loyalty.domain

import java.time.Instant

data class Lot(val id: Long, val remaining: Long, val expiresAt: Instant)

data class Consumption(val lotId: Long, val amount: Long)

/**
 * 사용 금액을 어떤 lot 에서 뺄지 정한다: 만료가 가까운 것부터(같으면 먼저 적립된 것부터).
 * 회원에게 가장 유리한 순서 — 곧 사라질 포인트를 먼저 쓰게 해 소멸을 줄인다.
 */
object LotAllocator {
    fun allocate(lots: List<Lot>, amount: Long, now: Instant): List<Consumption>? {
        require(amount > 0)
        var left = amount
        val result = mutableListOf<Consumption>()
        for (lot in lots.filter { it.remaining > 0 && it.expiresAt.isAfter(now) }.sortedWith(compareBy({ it.expiresAt }, { it.id }))) {
            if (left == 0L) break
            val take = minOf(left, lot.remaining)
            result += Consumption(lot.id, take)
            left -= take
        }
        return if (left == 0L) result else null
    }
}
