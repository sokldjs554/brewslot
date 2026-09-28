package com.brewslot.order.scheduling.application

import com.brewslot.common.BusinessTime
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.scheduling.domain.BrewScheduler
import com.brewslot.order.scheduling.domain.DrinkDemand
import com.brewslot.order.scheduling.domain.PickupTimeAdvisor
import com.brewslot.order.scheduling.domain.ProductionPlan
import com.brewslot.order.scheduling.domain.ScheduleResult
import com.brewslot.order.scheduling.domain.SlotGrid
import com.brewslot.order.scheduling.domain.SlotKey
import com.brewslot.order.scheduling.infra.JdbcCapacityLedger
import com.brewslot.order.scheduling.infra.RedisSlotUsageCache
import com.brewslot.web.BusinessException
import com.brewslot.web.ConflictException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** 동시에 들어온 다른 예약에 밀려 계획이 무효가 됨. 새 트랜잭션에서 재계획하면 성공할 수 있다. */
class SlotRaceLostException : RuntimeException("slot capacity changed concurrently")

class SlotUnavailableException(
    val requested: Instant,
    val bottleneck: String,
    val reason: String,
    val alternatives: List<Instant>,
    /** 같은 브랜드의 다른 매장 중 원하는 시각 그대로 가능한 곳 (주문 시에만 채운다) */
    val nearbyStores: List<Map<String, Any>> = emptyList(),
) : ConflictException(
        type = "pickup-slot-unavailable",
        message = "요청한 픽업 시각($requested)에는 제조 용량이 부족합니다.",
        extensions = mapOf(
            "requestedPickupAt" to requested.toString(),
            "bottleneckStation" to bottleneck,
            "reason" to reason,
            "alternatives" to alternatives.map { it.toString() },
            "nearbyStores" to nearbyStores,
        ),
    ) {
    fun withNearbyStores(stores: List<Map<String, Any>>) = SlotUnavailableException(requested, bottleneck, reason, alternatives, stores)
}

@Service
class SlotReservationService(
    private val ledger: JdbcCapacityLedger,
    private val cache: RedisSlotUsageCache,
    meterRegistry: MeterRegistry,
) {
    private val meters = meterRegistry

    fun gridOf(store: Store) = SlotGrid(store.capacity.slotMinutes)

    fun earliestSlotStart(store: Store, now: Instant): Instant = gridOf(store).ceil(now)

    /** 주문 가능한 픽업 후보 시각들 (영업시간 ∩ [now+lead, now+maxAdvance]) */
    fun candidateTimes(store: Store, now: Instant, from: Instant? = null, to: Instant? = null): List<Instant> {
        val grid = gridOf(store)
        val lower = grid.ceil(maxOf(now.plus(Duration.ofMinutes(store.capacity.minLeadMinutes.toLong())), from ?: Instant.MIN))
        val upper = minOf(now.plus(Duration.ofMinutes(store.capacity.maxAdvanceMinutes.toLong())), to ?: Instant.MAX)
        return generateSequence(lower) { it.plus(grid.slot) }
            .takeWhile { !it.isAfter(upper) }
            .filter { isWithinOpeningHours(store, it) }
            .toList()
    }

    fun validatePickupTime(store: Store, pickupAt: Instant, now: Instant) {
        val grid = gridOf(store)
        if (!grid.isAligned(pickupAt)) {
            throw BusinessException(
                "pickup-time-not-aligned",
                HttpStatus.BAD_REQUEST,
                "픽업 시각은 ${grid.slotMinutes}분 단위여야 합니다.",
            )
        }
        val earliest = now.plus(Duration.ofMinutes(store.capacity.minLeadMinutes.toLong()))
        val latest = now.plus(Duration.ofMinutes(store.capacity.maxAdvanceMinutes.toLong()))
        if (pickupAt.isBefore(earliest) || pickupAt.isAfter(latest) || !isWithinOpeningHours(store, pickupAt)) {
            throw BusinessException(
                "pickup-time-out-of-range",
                HttpStatus.UNPROCESSABLE_ENTITY,
                "픽업 가능한 시간 범위를 벗어났습니다.",
                mapOf("earliestPickupAt" to grid.ceil(earliest).toString()),
            )
        }
    }

    /**
     * 현재 트랜잭션 안에서 슬롯을 점유한다.
     * @throws SlotUnavailableException 신선도 창 안에 용량이 없을 때 (대안 시각 포함)
     * @throws SlotRaceLostException 계획 직후 다른 예약에 밀렸을 때 (호출자가 새 트랜잭션으로 재시도)
     */
    fun reserve(store: Store, orderId: UUID, pickupAt: Instant, demands: List<DrinkDemand>, now: Instant): ProductionPlan {
        val grid = gridOf(store)
        val scheduler = BrewScheduler(grid)
        val window = windowFor(pickupAt, demands, grid.slotMinutes)
        val snapshot = ledger.snapshot(store.id, store.capacity, window.first, window.second)

        val plan = when (val result = scheduler.schedule(pickupAt, demands, snapshot, earliestSlotStart(store, now))) {
            is ScheduleResult.Feasible -> result.plan
            is ScheduleResult.Infeasible -> {
                meters.counter("brewslot.slot.rejections", "store", store.id.toString(), "station", result.bottleneck.name).increment()
                throw SlotUnavailableException(
                    pickupAt,
                    result.bottleneck.name,
                    result.reason.name,
                    alternatives(store, pickupAt, demands, now),
                )
            }
        }

        ledger.ensureSlots(store.id, store.capacity, plan.allocations)
        if (!ledger.tryReserve(store.id, orderId, plan.allocations, now)) throw SlotRaceLostException()
        refreshCacheAfterCommit(store.id, plan.allocations.map { it.key })
        return plan
    }

    fun confirm(orderId: UUID, now: Instant) {
        ledger.confirm(orderId, now)
    }

    fun release(storeId: Long, orderId: UUID, now: Instant) {
        val keys = ledger.release(orderId, now)
        refreshCacheAfterCommit(storeId, keys)
    }

    /** 매장 변경 확정: 원래 매장 자리를 풀고, 새 매장에 잡아 둔 자리([transferId])를 주문의 확정 예약으로 넘긴다. */
    fun transferReservation(fromStoreId: Long, orderId: UUID, transferId: UUID, now: Instant): Boolean {
        release(fromStoreId, orderId, now)
        return ledger.handOver(transferId, orderId, now)
    }

    fun alternatives(store: Store, requested: Instant, demands: List<DrinkDemand>, now: Instant): List<Instant> {
        val candidates = candidateTimes(store, now, requested.minus(ALTERNATIVE_RADIUS), requested.plus(ALTERNATIVE_RADIUS))
        if (candidates.isEmpty()) return emptyList()
        val grid = gridOf(store)
        val window = windowFor(candidates.first(), demands, grid.slotMinutes).first to candidates.last()
        val snapshot = ledger.snapshot(store.id, store.capacity, window.first, window.second)
        return PickupTimeAdvisor(grid, BrewScheduler(grid))
            .nearestAlternatives(requested, candidates, demands, snapshot, earliestSlotStart(store, now))
    }

    private fun refreshCacheAfterCommit(storeId: Long, keys: List<SlotKey>) {
        if (keys.isEmpty()) return
        val action = { cache.write(storeId, ledger.rowsFor(storeId, keys)) }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                },
            )
        } else {
            action()
        }
    }

    private fun isWithinOpeningHours(store: Store, pickupAt: Instant): Boolean {
        val local: LocalDateTime = LocalDateTime.ofInstant(pickupAt, BusinessTime.ZONE)
        val time = local.toLocalTime()
        return !time.isBefore(store.openTime.plusMinutes(store.capacity.slotMinutes.toLong())) && !time.isAfter(store.closeTime)
    }

    companion object {
        private val ALTERNATIVE_RADIUS: Duration = Duration.ofMinutes(60)

        /** 스케줄링에 필요한 슬롯 범위: [가장 이른 신선도 창 시작, 픽업 시각) */
        fun windowFor(pickupAt: Instant, demands: List<DrinkDemand>, slotMinutes: Int): Pair<Instant, Instant> {
            val maxFreshness = demands.maxOf { it.freshnessMinutes }
            val depthSlots = maxOf(1, (maxFreshness + slotMinutes - 1) / slotMinutes)
            return pickupAt.minus(Duration.ofMinutes(depthSlots.toLong() * slotMinutes)) to pickupAt
        }
    }
}
