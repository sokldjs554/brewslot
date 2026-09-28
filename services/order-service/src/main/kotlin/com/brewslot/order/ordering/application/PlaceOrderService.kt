package com.brewslot.order.ordering.application

import com.brewslot.common.Money
import com.brewslot.common.Uuid7
import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.config.OrderProperties
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.scheduling.application.CartItem
import com.brewslot.order.scheduling.application.PickupAvailabilityService
import com.brewslot.order.scheduling.application.SlotRaceLostException
import com.brewslot.order.scheduling.application.SlotReservationService
import com.brewslot.order.scheduling.application.SlotUnavailableException
import com.brewslot.web.UnprocessableException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class PlaceOrderCommand(
    val memberId: Long,
    val storeId: Long,
    val pickupAt: Instant,
    val items: List<CartItem>,
    val pointsToUse: Long,
    val idempotencyKey: String,
    val couponId: UUID? = null,
    val couponAmount: Long = 0,
) {
    /** 같은 Idempotency-Key 로 "다른" 요청이 오면 거절하기 위한 요청 지문 */
    fun fingerprint(): String {
        val canonical = buildString {
            append(storeId).append('|').append(pickupAt).append('|').append(pointsToUse).append('|')
            append(couponId).append('|').append(couponAmount).append('|')
            items.sortedBy { it.menuItemId }.forEach { append(it.menuItemId).append('x').append(it.quantity).append(',') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class PlaceOrderResult(val order: Order, val replayed: Boolean)

/**
 * 주문 생성 = 슬롯 임시 점유 + 주문 저장 + OrderPlaced 발행을 하나의 로컬 트랜잭션으로 처리한다.
 * (결제는 별도 단계 — 사용자가 결제수단을 고르는 동안 자리를 잡아 두는 "좌석 선점" 모델)
 */
@Service
class PlaceOrderService(
    private val catalog: CatalogService,
    private val slots: SlotReservationService,
    private val orders: JdbcOrderRepository,
    private val events: OrderEvents,
    private val props: OrderProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    meterRegistry: MeterRegistry,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val raceRetries = meterRegistry.counter("brewslot.slot.reservation.race_retries")

    fun place(cmd: PlaceOrderCommand): PlaceOrderResult {
        replay(cmd)?.let { return it }

        val store = catalog.store(cmd.storeId)
        val now = clock.instant()
        slots.validatePickupTime(store, cmd.pickupAt, now)
        val demands = PickupAvailabilityService.demandsOf(store, cmd.items)
        val lines = linesOf(store, cmd.items)

        repeat(props.maxReservationAttempts) {
            try {
                return tx.execute {
                    val order = Order.place(
                        id = Uuid7.next(clock),
                        memberId = cmd.memberId,
                        storeId = store.id,
                        brandId = store.brandId,
                        lines = lines,
                        promisedPickupAt = cmd.pickupAt,
                        pointAmount = Money(cmd.pointsToUse),
                        holdExpiresAt = now.plus(props.holdTtl),
                        now = now,
                        couponId = cmd.couponId,
                        couponAmount = Money(cmd.couponAmount),
                    )
                    slots.reserve(store, order.id, cmd.pickupAt, demands, now)
                    orders.insert(order, cmd.idempotencyKey, cmd.fingerprint())
                    events.placed(order)
                    PlaceOrderResult(order, replayed = false)
                }!!
            } catch (e: SlotRaceLostException) {
                raceRetries.increment()
            } catch (e: DuplicateKeyException) {
                // 같은 Idempotency-Key 의 동시 요청: 먼저 커밋된 쪽의 결과를 돌려준다.
                return replay(cmd) ?: throw e
            }
        }
        throw SlotUnavailableException(cmd.pickupAt, "UNKNOWN", "CONTENTION", slots.alternatives(store, cmd.pickupAt, demands, now))
    }

    private fun replay(cmd: PlaceOrderCommand): PlaceOrderResult? {
        val (existing, fingerprint) = orders.findByIdempotencyKey(cmd.memberId, cmd.idempotencyKey) ?: return null
        if (fingerprint != cmd.fingerprint()) {
            throw UnprocessableException("idempotency-key-reused", "같은 Idempotency-Key 로 다른 내용의 주문을 요청했습니다.")
        }
        return PlaceOrderResult(existing, replayed = true)
    }

    private fun linesOf(store: Store, items: List<CartItem>): List<OrderLine> = items.mapIndexed { idx, item ->
        val menu = store.menuItem(item.menuItemId)!!
        OrderLine(idx + 1, menu.id, menu.name, menu.price, item.quantity, menu.station, menu.loadUnits, menu.freshnessMinutes)
    }
}
