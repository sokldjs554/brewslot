package com.brewslot.order.ordering.application

import com.brewslot.order.ordering.domain.CancelReason
import com.brewslot.order.ordering.infra.JdbcOrderRepository
import com.brewslot.order.ordering.saga.CheckoutSagaOrchestrator
import com.brewslot.order.ordering.saga.JdbcCheckoutSagaRepository
import com.brewslot.order.scheduling.application.SlotReservationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 시간에 의해 일어나는 상태 전이.
 * - 결제를 시작하지 않은 주문의 슬롯 점유 해제
 * - 응답이 오지 않는 결제 Saga 의 보상
 * 여러 인스턴스가 떠 있어도 SKIP LOCKED 로 같은 주문을 중복 처리하지 않는다.
 */
@Component
class ExpiryJobs(
    private val orders: JdbcOrderRepository,
    private val sagas: JdbcCheckoutSagaRepository,
    private val orchestrator: CheckoutSagaOrchestrator,
    private val slots: SlotReservationService,
    private val events: OrderEvents,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${brewslot.order.expiry-poll-ms:5000}")
    fun expireHolds(): Int = tx.execute {
        val now = clock.instant()
        val ids = orders.lockExpiredHolds(now, BATCH)
        ids.forEach { id ->
            val order = orders.findById(id) ?: return@forEach
            order.cancel(CancelReason.HOLD_EXPIRED, now)
            orders.update(order)
            slots.release(order.storeId, order.id, now)
            events.cancelled(order)
        }
        if (ids.isNotEmpty()) log.info("expired {} unpaid holds", ids.size)
        ids.size
    } ?: 0

    @Scheduled(fixedDelayString = "\${brewslot.order.expiry-poll-ms:5000}")
    fun timeoutSagas(): Int = tx.execute {
        val ids = sagas.lockTimedOut(clock.instant(), BATCH)
        ids.forEach { orchestrator.timeout(it) }
        ids.size
    } ?: 0

    companion object {
        private const val BATCH = 100
    }
}
