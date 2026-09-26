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
 * 여러 인스턴스가 떠 있어도 중복 처리하지 않는다: 점유 만료는 SKIP LOCKED, Saga 타임아웃은 주문 행 잠금 후 상태 재확인.
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

    /**
     * 건마다 별도 트랜잭션(orchestrator.timeout 의 @Transactional)으로 처리한다. 한 건의 실패가 배치 전체를 되돌리지 않고,
     * 여러 인스턴스가 같은 건을 집어도 주문 행 잠금 + "Saga 가 아직 RUNNING 인가" 재확인으로 한 번만 반영된다.
     */
    @Scheduled(fixedDelayString = "\${brewslot.order.expiry-poll-ms:5000}")
    fun timeoutSagas(): Int {
        val ids = sagas.findTimedOut(clock.instant(), BATCH)
        ids.forEach { id ->
            runCatching { orchestrator.timeout(id) }
                .onFailure { log.warn("saga timeout handling failed (will retry): orderId={}, cause={}", id, it.toString()) }
        }
        return ids.size
    }

    companion object {
        private const val BATCH = 100
    }
}
