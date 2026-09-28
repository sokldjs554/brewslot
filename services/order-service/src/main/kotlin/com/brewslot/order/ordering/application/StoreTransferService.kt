package com.brewslot.order.ordering.application

import com.brewslot.common.Uuid7
import com.brewslot.order.catalog.application.CatalogService
import com.brewslot.order.catalog.domain.ChosenDrink
import com.brewslot.order.catalog.domain.MenuMatch
import com.brewslot.order.config.OrderProperties
import com.brewslot.order.ordering.domain.Order
import com.brewslot.order.ordering.domain.OrderLine
import com.brewslot.order.ordering.domain.OrderStatus
import com.brewslot.order.ordering.domain.StoreTransfer
import com.brewslot.order.ordering.domain.TransferFailReason
import com.brewslot.order.ordering.domain.TransferStatus
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.ordering.infra.JdbcStoreTransferRepository
import com.brewslot.order.scheduling.application.CartItem
import com.brewslot.order.scheduling.application.PickupAvailabilityService
import com.brewslot.order.scheduling.application.SlotRaceLostException
import com.brewslot.order.scheduling.application.SlotReservationService
import com.brewslot.order.scheduling.application.SlotUnavailableException
import com.brewslot.web.ConflictException
import com.brewslot.web.NotFoundException
import com.brewslot.web.UnprocessableException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class RequestTransferCommand(
    val memberId: Long,
    val orderId: UUID,
    val toStoreId: Long,
    val pickupAt: Instant,
    val idempotencyKey: String,
) {
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest("$orderId|$toStoreId|$pickupAt".toByteArray())
        .joinToString("") { "%02x".format(it) }
}

data class TransferResult(val transfer: StoreTransfer, val replayed: Boolean)

/** 매장 변경 후보 한 곳 */
data class TransferCandidate(
    val storeId: Long,
    val storeName: String,
    val compatible: Boolean,
    /** 옮길 수 없는 이유: NOT_SOLD | SOLD_OUT | PRICE_DIFFERS */
    val reason: String?,
    val notSold: List<String>,
    val soldOut: List<String>,
    val priceDiffers: List<String>,
    val sameTimeAvailable: Boolean,
    val nearestTimes: List<Instant>,
)

data class TransferOptions(
    val orderId: UUID,
    val currentStoreId: Long,
    val promisedPickupAt: Instant,
    /** 지금 매장 변경을 요청할 수 있는가. 아니면 [blockedReason] */
    val transferable: Boolean,
    val blockedReason: String?,
    val candidates: List<TransferCandidate>,
)

/**
 * 매장 변경 (ADR-0013). 결제된 주문을 같은 브랜드의 다른 매장으로 옮긴다.
 *
 * 1. 요청: 새 매장 자리를 transfer ID 로 먼저 잡는다(HELD). 원래 주문 · 원래 자리 · 결제는 건드리지 않는다.
 * 2. 새 매장 수락: 한 트랜잭션에서 원래 자리 해제 → 새 자리를 주문으로 넘김 → 주문 매장 · 항목 · 약속 시각 변경 → OrderTransferred.
 * 3. 거절 · 기한 초과 · 원래 매장이 먼저 제조 시작 · 고객 취소: 새 자리만 풀고 OrderTransferFailed. 원래 주문은 그대로.
 *
 * 잠금 순서는 모든 경로에서 "주문 행 → transfer 행 → 슬롯 행(스테이션 · 시각 순)" 이다.
 */
@Service
class StoreTransferService(
    private val catalog: CatalogService,
    private val slots: SlotReservationService,
    private val orders: JdbcOrderRepository,
    private val transfers: JdbcStoreTransferRepository,
    private val alternatives: StoreAlternatives,
    private val events: OrderEvents,
    private val props: OrderProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val meters = meterRegistry

    fun options(memberId: Long, orderId: UUID): TransferOptions {
        val order = ownOrder(memberId, orderId)
        val now = clock.instant()
        val blocked = blockedReason(order)
        val source = catalog.store(order.storeId)
        val candidates = alternatives.evaluateOthers(source, drinksOf(order), order.promisedPickupAt, now).map { e ->
            val inc = e.match as? MenuMatch.Incompatible
            TransferCandidate(
                storeId = e.store.id,
                storeName = e.store.name,
                compatible = inc == null,
                reason = inc?.reason,
                notSold = inc?.notSold ?: emptyList(),
                soldOut = inc?.soldOut ?: emptyList(),
                priceDiffers = inc?.priceDiffers ?: emptyList(),
                sameTimeAvailable = e.requestedTimeFeasible,
                nearestTimes = e.nearestTimes,
            )
        }
        return TransferOptions(order.id, order.storeId, order.promisedPickupAt, blocked == null, blocked, candidates)
    }

    fun request(cmd: RequestTransferCommand): TransferResult {
        replay(cmd)?.let { return it }
        val now = clock.instant()
        val order = ownOrder(cmd.memberId, cmd.orderId)
        blockedReason(order)?.let { throw ConflictException(it, blockedMessage(it), mapOf("currentStatus" to order.status.name)) }

        val target = catalog.store(cmd.toStoreId)
        if (target.brandId != order.brandId || target.id == order.storeId) {
            throw UnprocessableException("transfer-store-not-eligible", "같은 브랜드의 다른 매장으로만 변경할 수 있습니다.")
        }
        val match = alternatives.evaluate(target, drinksOf(order), cmd.pickupAt, now).match
        if (match is MenuMatch.Incompatible) {
            throw UnprocessableException(
                "transfer-menu-incompatible",
                "${target.name} 에서는 같은 음료를 같은 가격에 만들 수 없습니다.",
                mapOf("reason" to match.reason, "notSold" to match.notSold, "soldOut" to match.soldOut, "priceDiffers" to match.priceDiffers),
            )
        }
        val items = (match as MenuMatch.Compatible).items
        val newLines = items.mapIndexed { i, (m, q) -> OrderLine(i + 1, m.id, m.name, m.price, q, m.station, m.loadUnits, m.freshnessMinutes) }
        val demands = PickupAvailabilityService.demandsOf(target, items.map { (m, q) -> CartItem(m.id, q) })
        slots.validatePickupTime(target, cmd.pickupAt, now)

        repeat(props.maxReservationAttempts) {
            try {
                return tx.execute {
                    val locked = orders.findByIdForUpdate(order.id)!!
                    blockedReason(locked)?.let { throw ConflictException(it, blockedMessage(it), mapOf("currentStatus" to locked.status.name)) }
                    val transfer = StoreTransfer.request(
                        Uuid7.next(clock),
                        locked,
                        target.id,
                        cmd.pickupAt,
                        newLines,
                        now,
                        now.plus(props.transferAcceptTimeout),
                    )
                    slots.reserve(target, transfer.id, cmd.pickupAt, demands, now)
                    transfers.insert(transfer, cmd.idempotencyKey, cmd.fingerprint())
                    count("requested")
                    TransferResult(transfer, replayed = false)
                }!!
            } catch (e: SlotRaceLostException) {
                // 다른 주문에 밀림 → 최신 스냅샷으로 재계획
            } catch (e: DuplicateKeyException) {
                // 같은 Idempotency-Key 의 재시도면 먼저 커밋된 결과, 아니면 다른 변경 요청이 이미 진행 중
                return replay(cmd) ?: throw ConflictException("transfer-in-progress", "이미 진행 중인 매장 변경 요청이 있습니다.")
            }
        }
        throw SlotUnavailableException(cmd.pickupAt, "UNKNOWN", "CONTENTION", slots.alternatives(target, cmd.pickupAt, demands, now))
    }

    fun find(memberId: Long, orderId: UUID, transferId: UUID): StoreTransfer {
        ownOrder(memberId, orderId)
        return transfers.findById(transferId)?.takeIf { it.orderId == orderId } ?: throw NotFoundException("transfer", transferId)
    }

    fun pendingForStore(storeId: Long): List<StoreTransfer> = transfers.pendingForStore(storeId)

    /**
     * 새 매장 수락. 태블릿이 응답을 못 받고 다시 눌러도 같은 결과(COMPLETED)를 돌려준다.
     * 이미 거절 · 만료 · 중단된 요청이면 그 상태를 그대로 돌려준다(호출자가 409 로 알림).
     */
    fun accept(storeId: Long, transferId: UUID): StoreTransfer = decide(storeId, transferId) { order, t, now ->
        if (t.isOverdue(now)) {
            fail(t, now) { it.expire(now) }
            return@decide
        }
        if (order.status != OrderStatus.PAID || order.storeId != t.fromStoreId) {
            // 방어: 원래 매장 제조 시작 · 취소는 그 트랜잭션에서 이미 중단 처리된다(abortOpenTransfer).
            fail(t, now) { it.abort(TransferFailReason.ORIGINAL_STORE_STARTED, now) }
            return@decide
        }
        val fromStoreId = order.storeId
        order.transferTo(t.toStoreId, t.newLines, t.pickupAt)
        check(slots.transferReservation(fromStoreId, order.id, t.id, now)) { "transfer hold ${t.id} not found" }
        orders.update(order)
        orders.replaceLines(order)
        t.complete(now)
        check(transfers.close(t))
        events.transferred(order, t)
        count("completed")
    }

    fun refuse(storeId: Long, transferId: UUID): StoreTransfer = decide(storeId, transferId) { _, t, now ->
        fail(t, now) { it.refuse(now) }
    }

    /** 기한 안에 새 매장이 답하지 않은 요청을 정리한다. 여러 인스턴스가 돌아도 조건부 UPDATE 로 한 번만 닫힌다. */
    @Scheduled(fixedDelayString = "\${brewslot.order.transfer-poll-ms:5000}")
    fun expireOverdue(): Int {
        var expired = 0
        transfers.overdueIds(clock.instant(), 100).forEach { id ->
            val t = transfers.findById(id) ?: return@forEach
            try {
                decide(t.toStoreId, id) { _, locked, now ->
                    if (locked.isOverdue(now)) {
                        fail(locked, now) { it.expire(now) }
                        expired++
                    }
                }
            } catch (e: Exception) {
                log.warn("transfer expiry failed for {}", id, e)
            }
        }
        return expired
    }

    /**
     * 주문 쪽 상황이 바뀌어(원래 매장 제조 시작 · 매장 거절 · 고객 취소) 진행 중인 변경 요청을 중단한다.
     * 호출자는 이미 주문 행을 잠근 트랜잭션 안에 있어야 한다(잠금 순서: 주문 → transfer).
     */
    fun abortOpenTransfer(order: Order, reason: TransferFailReason) {
        val t = transfers.findOpenByOrderForUpdate(order.id) ?: return
        fail(t, clock.instant()) { it.abort(reason, clock.instant()) }
    }

    private inline fun fail(t: StoreTransfer, now: Instant, transition: (StoreTransfer) -> Unit) {
        transition(t)
        slots.release(t.toStoreId, t.id, now)
        check(transfers.close(t))
        events.transferFailed(t)
        count(t.status.name.lowercase())
    }

    private fun decide(storeId: Long, transferId: UUID, block: (Order, StoreTransfer, Instant) -> Unit): StoreTransfer {
        val peek = transfers.findById(transferId)?.takeIf { it.toStoreId == storeId } ?: throw NotFoundException("transfer", transferId)
        return tx.execute {
            val order = orders.findByIdForUpdate(peek.orderId)!!
            val t = transfers.findById(transferId, forUpdate = true)!!
            if (t.status.isOpen) block(order, t, clock.instant())
            t
        }!!
    }

    private fun replay(cmd: RequestTransferCommand): TransferResult? {
        val (existing, hash) = transfers.findByIdempotencyKey(cmd.memberId, cmd.idempotencyKey) ?: return null
        if (hash != cmd.fingerprint() || existing.orderId != cmd.orderId) {
            throw UnprocessableException("idempotency-key-reused", "같은 Idempotency-Key 로 다른 내용의 매장 변경을 요청했습니다.")
        }
        return TransferResult(existing, replayed = true)
    }

    private fun ownOrder(memberId: Long, orderId: UUID): Order =
        orders.findById(orderId)?.takeIf { it.memberId == memberId } ?: throw NotFoundException("order", orderId)

    private fun blockedReason(order: Order): String? = when {
        order.status != OrderStatus.PAID -> "transfer-not-allowed"
        transfers.hasCompleted(order.id) -> "transfer-limit-reached"
        transfers.byOrder(order.id).any { it.status == TransferStatus.AWAITING_STORE } -> "transfer-in-progress"
        else -> null
    }

    private fun blockedMessage(type: String) = when (type) {
        "transfer-not-allowed" -> "결제가 끝났고 제조가 시작되기 전인 주문만 매장을 바꿀 수 있습니다."
        "transfer-limit-reached" -> "매장 변경은 주문당 한 번만 할 수 있습니다."
        else -> "이미 진행 중인 매장 변경 요청이 있습니다."
    }

    private fun count(outcome: String) = meters.counter("brewslot.order.transfer", "outcome", outcome).increment()

    companion object {
        fun drinksOf(order: Order) = order.lines.map { ChosenDrink(it.name, it.unitPrice, it.quantity) }
    }
}
