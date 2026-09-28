package com.brewslot.order.ordering

import com.brewslot.common.Money
import com.brewslot.order.catalog.domain.CapacityProfile
import com.brewslot.order.catalog.domain.GeoPoint
import com.brewslot.order.catalog.domain.MenuItem
import com.brewslot.order.catalog.domain.MenuMatch
import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.ordering.application.PickupRiskDetector
import com.brewslot.order.ordering.application.StoreEvaluation
import com.brewslot.order.ordering.domain.PickupDelay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class PickupDelayTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    @Test
    fun `매장 지연은 가장 오래 밀린 주문 기준, 곧 받을 주문만 먼저 알린다`() {
        assertThat(PickupDelay.storeDelayMinutes(now.minusSeconds(61), now)).isEqualTo(2)
        assertThat(PickupDelay.storeDelayMinutes(now.plusSeconds(10), now)).isZero()

        val horizon = Duration.ofMinutes(45)
        assertThat(PickupDelay.shouldAlert(8, 5, now.plusSeconds(20 * 60), now, horizon)).isTrue()
        assertThat(PickupDelay.shouldAlert(4, 5, now.plusSeconds(20 * 60), now, horizon)).isFalse() // 조금 밀린 건 알리지 않는다
        assertThat(PickupDelay.shouldAlert(8, 5, now.plusSeconds(60 * 60), now, horizon)).isFalse() // 먼 주문은 그 사이 따라잡을 수 있다
        assertThat(PickupDelay.shouldAlert(8, 5, now.minusSeconds(60), now, horizon)).isFalse() // 이미 받을 시각이 지남
    }

    @Test
    fun `도보 시간 - 골목 우회를 감안해 올림`() {
        val yeoksam = GeoPoint(37.5007, 127.0365)
        assertThat(yeoksam.walkMinutesTo(GeoPoint(37.5006, 127.0333))).isEqualTo(6)
        assertThat(yeoksam.walkMinutesTo(GeoPoint(37.3947, 127.1112))).isGreaterThan(60)
    }

    private fun store(id: Long) = Store(
        id, 1, "매장$id", LocalTime.MIN, LocalTime.of(23, 59), CapacityProfile(5, mapOf(Station.ESPRESSO to 8), 5, 1440), emptyList(),
    )

    private fun ok(id: Long, same: Boolean, walk: Int, vararg times: Instant) = StoreEvaluation(
        store(id),
        MenuMatch.Compatible(listOf(MenuItem(id, id, "라떼", Money(4500), Station.ESPRESSO, 2, 5, true) to 1)),
        same,
        times.toList(),
        walk,
    )

    @Test
    fun `제안 - 가까운 매장의 같은 시각 먼저, 없으면 원래 매장에서 기다리는 것보다 이른 가장 가까운 시각, 없으면 제안하지 않음`() {
        val promised = now.plusSeconds(30 * 60)
        val later = { m: Long -> promised.plusSeconds(m * 60) }
        val none = MenuMatch.Incompatible(listOf("라떼"), emptyList(), emptyList())

        val same = PickupRiskDetector.suggest(listOf(ok(104, false, 6, later(5)), ok(103, true, 13)), promised, 10)!!
        assertThat(same.storeId).isEqualTo(103)
        assertThat(same.pickupAt).isEqualTo(promised)

        val earlier = PickupRiskDetector.suggest(listOf(ok(104, false, 6, later(15), later(5))), promised, 10)!!
        assertThat(earlier.pickupAt).isEqualTo(later(5)) // 10분 늦어지는 것보다 5분 뒤가 낫다

        assertThat(PickupRiskDetector.suggest(listOf(ok(104, false, 6, later(15))), promised, 10)).isNull() // 기다리는 게 낫다
        assertThat(PickupRiskDetector.suggest(listOf(StoreEvaluation(store(102), none, false, emptyList(), 14)), promised, 10)).isNull()
    }
}
