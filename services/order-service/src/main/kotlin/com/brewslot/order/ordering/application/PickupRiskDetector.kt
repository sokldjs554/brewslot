package com.brewslot.order.ordering.application

import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.PickupAtRisk
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.MenuMatch
import com.brewslot.order.config.OrderProperties
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.domain.PickupDelay
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.ordering.infra.JdbcPickupRiskRepository
import com.brewslot.order.ordering.infra.JdbcStoreTransferRepository
import com.brewslot.order.ordering.infra.PickupRisk
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

data class Suggestion(val storeId: Long, val storeName: String, val pickupAt: Instant, val walkMinutes: Int?)

/**
 * 매장이 밀리면 고객이 앱을 열기 전에 먼저 알린다 (ADR-0014).
 *
 * 매장 변경 버튼만 두면 아무도 누르지 않는다 — 고객은 매장이 밀리고 있다는 것을 모르기 때문이다.
 * 그래서 시스템이 지연을 감지하고, 옮길 만한 근처 매장 · 시각이 있으면 함께 제안한다. 고객은 한 번 눌러 옮긴다.
 */
@Component
class PickupRiskDetector(
    private val risks: JdbcPickupRiskRepository,
    private val orders: JdbcOrderRepository,
    private val transfers: JdbcStoreTransferRepository,
    private val catalog: CatalogService,
    private val alternatives: StoreAlternatives,
    private val outbox: Outbox,
    private val props: OrderProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val alerts = meterRegistry

    @Scheduled(fixedDelayString = "\${brewslot.order.risk-poll-ms:30000}")
    fun scan(): Int {
        val now = clock.instant()
        var alerted = 0
        risks.lateStores(now).forEach { (storeId, overdueSince) ->
            val delay = PickupDelay.storeDelayMinutes(overdueSince, now)
            if (delay < props.delayAlertMinutes) return@forEach
            risks.unalertedOrders(storeId, now, now.plus(props.delayAlertHorizon), 200).forEach { orderId ->
                try {
                    if (alert(orderId, storeId, delay, now)) alerted++
                } catch (e: Exception) {
                    log.warn("pickup risk alert failed for {}", orderId, e)
                }
            }
        }
        return alerted
    }

    private fun alert(orderId: java.util.UUID, storeId: Long, delay: Int, now: Instant): Boolean = tx.execute {
        val order = orders.findByIdForUpdate(orderId) ?: return@execute false
        if (order.status != OrderStatus.PAID || order.storeId != storeId) return@execute false
        if (!PickupDelay.shouldAlert(delay, props.delayAlertMinutes, order.promisedPickupAt, now, props.delayAlertHorizon)) return@execute false

        val source = catalog.store(storeId)
        // 이미 한 번 옮긴 주문은 다시 옮길 수 없으므로 지연만 알린다
        val suggestion = if (transfers.hasCompleted(order.id)) {
            null
        } else {
            suggest(alternatives.evaluateOthers(source, StoreTransferService.drinksOf(order), order.promisedPickupAt, now), order.promisedPickupAt, delay)
        }
        val inserted = risks.insert(
            PickupRisk(order.id, storeId, delay, suggestion?.storeId, suggestion?.pickupAt, suggestion?.walkMinutes, now),
        )
        if (inserted) {
            outbox.publish(
                Topics.ORDER_EVENTS,
                order.id.toString(),
                PickupAtRisk(
                    order.id.toString(), order.memberId, storeId, source.name, order.promisedPickupAt, delay,
                    suggestion?.storeId, suggestion?.storeName, suggestion?.pickupAt, suggestion?.walkMinutes, now,
                ),
            )
            alerts.counter("brewslot.pickup.risk.alerts", "suggested", (suggestion != null).toString()).increment()
        }
        inserted
    }!!

    companion object {
        /**
         * 가까운 매장부터(입력이 도보 순) 같은 시각 그대로 되는 곳을 먼저, 없으면 원래 매장에서 늦어지는 것보다
         * 이른 가장 가까운 시각. 원래 매장에서 기다리는 것보다 나은 제안이 없으면 제안하지 않는다.
         */
        fun suggest(evaluations: List<StoreEvaluation>, promised: Instant, delayMinutes: Int): Suggestion? {
            val compatible = evaluations.filter { it.match is MenuMatch.Compatible }
            compatible.firstOrNull { it.requestedTimeFeasible }?.let { return Suggestion(it.store.id, it.store.name, promised, it.walkMinutes) }
            return compatible.asSequence()
                .mapNotNull { e ->
                    e.nearestTimes.filter { PickupDelay.isBetter(it, promised, delayMinutes) }
                        .minByOrNull { java.time.Duration.between(promised, it).abs() }
                        ?.let { Suggestion(e.store.id, e.store.name, it, e.walkMinutes) }
                }
                .firstOrNull()
        }
    }
}
