package com.brewslot.payment.application

import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.ChargePayment
import com.brewslot.messaging.contract.PaymentCaptured
import com.brewslot.messaging.contract.PaymentFailed
import com.brewslot.messaging.contract.PaymentRefunded
import com.brewslot.messaging.contract.RefundPayment
import com.brewslot.messaging.inbox.Inbox
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.payment.domain.Payment
import com.brewslot.payment.domain.PaymentStatus
import com.brewslot.payment.fakepg.PgApproval
import com.brewslot.payment.fakepg.PgClient
import com.brewslot.payment.fakepg.PgTimeoutException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 결제 처리의 핵심 원칙: **외부 PG 호출을 DB 트랜잭션 안에 두지 않는다.**
 *
 *  tx1  결제 요청 기록(REQUESTED) + Inbox 처리완료 표시     ← 여기서 커밋
 *  ───  PG 승인 호출 (수백 ms ~ 수 초, 실패/타임아웃 가능)
 *  tx2  결과 반영(CAPTURED/FAILED/UNKNOWN) + 결과 이벤트 Outbox
 *
 * PG 호출 동안 DB 커넥션과 행 잠금을 쥐고 있지 않으므로 PG 가 느려져도 커넥션 풀이 고갈되지 않는다.
 * tx1 이후 프로세스가 죽거나 PG 응답을 못 받으면 결제는 REQUESTED/UNKNOWN 으로 남고,
 * [recoverUnresolved] 가 PG 거래 조회로 결과를 확정한다.
 */
@Service
class PaymentService(
    private val payments: JdbcPaymentRepository,
    private val pg: PgClient,
    private val inbox: Inbox,
    private val outbox: Outbox,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    private val meterRegistry: MeterRegistry,
    @Value("\${brewslot.payment.unresolved-grace:10s}") private val unresolvedGrace: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun charge(envelope: EventEnvelope, cmd: ChargePayment) {
        val orderId = UUID.fromString(cmd.orderId)
        var created = false
        inbox.process(CONSUMER, envelope) {
            created = payments.insertRequested(
                Payment(
                    UUID.randomUUID(),
                    orderId,
                    cmd.memberId,
                    cmd.storeId,
                    cmd.brandId,
                    cmd.amount,
                    cmd.cardToken,
                    PaymentStatus.REQUESTED,
                    null,
                    null,
                    null,
                    false,
                    clock.instant(),
                    null,
                    null,
                ),
            )
        }
        if (!created) return // 같은 주문의 결제 요청 중복 → 최초 요청의 결과가 이미 발행됐거나 복구 대상이다
        val payment = payments.findByOrderId(orderId)!!

        val result = try {
            pg.approve(orderId, payment.amount, payment.cardToken)
        } catch (e: PgTimeoutException) {
            log.warn("PG timeout, outcome unknown: orderId={}", orderId)
            tx.executeWithoutResult { payments.markUnknown(orderId, clock.instant()) }
            count("unknown")
            return
        }
        when (result) {
            is PgApproval.Approved -> onApproved(payment, result.transactionId)
            is PgApproval.Declined -> onDeclined(payment, result.reason)
        }
    }

    /** 보상 명령. 결제가 없거나 실패했으면 할 일이 없고, 결과 미확정이면 "환불 예약" 만 해 둔다. */
    fun refund(envelope: EventEnvelope, cmd: RefundPayment) {
        val orderId = UUID.fromString(cmd.orderId)
        inbox.process(CONSUMER, envelope) { payments.requestRefund(orderId, clock.instant()) }
        val payment = payments.findByOrderId(orderId) ?: return
        when (payment.status) {
            PaymentStatus.CAPTURED -> cancelAtPg(payment)
            PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN -> log.info("refund reserved until outcome is known: {}", orderId)
            PaymentStatus.FAILED, PaymentStatus.REFUNDED -> Unit
        }
    }

    @Scheduled(fixedDelayString = "\${brewslot.payment.recovery-poll-ms:5000}")
    fun recoverUnresolved(): Int {
        val ids = payments.findUnresolved(clock.instant().minus(unresolvedGrace), 100)
        ids.forEach { orderId ->
            val payment = payments.findByOrderId(orderId) ?: return@forEach
            val record = pg.lookup(orderId)
            if (record != null && record.cancelId == null) {
                log.info("recovered payment as CAPTURED via PG lookup: {}", orderId)
                onApproved(payment, record.approveId)
            } else if (record == null) {
                log.info("recovered payment as FAILED (no PG record): {}", orderId)
                onDeclined(payment, "PG_NO_RECORD")
            }
        }
        return ids.size
    }

    private fun onApproved(payment: Payment, pgTxId: String) {
        val now = clock.instant()
        val applied = tx.execute {
            val ok = payments.markCaptured(payment.orderId, pgTxId, now)
            if (ok) {
                outbox.publish(
                    Topics.PAYMENT_EVENTS,
                    payment.orderId.toString(),
                    PaymentCaptured(
                        payment.id.toString(),
                        payment.orderId.toString(),
                        payment.memberId,
                        payment.storeId,
                        payment.brandId,
                        payment.amount,
                        pgTxId,
                        now,
                    ),
                )
            }
            ok
        } ?: false
        if (!applied) return
        count("captured")
        // 결과를 모르는 사이 보상(환불) 명령이 먼저 와 있었다면 이제 실행한다.
        val latest = payments.findByOrderId(payment.orderId)!!
        if (latest.refundRequested) cancelAtPg(latest)
    }

    private fun onDeclined(payment: Payment, reason: String) {
        val now = clock.instant()
        tx.executeWithoutResult {
            if (payments.markFailed(payment.orderId, reason, now)) {
                outbox.publish(Topics.PAYMENT_EVENTS, payment.orderId.toString(), PaymentFailed(payment.orderId.toString(), reason, now))
                count("failed")
            }
        }
    }

    private fun cancelAtPg(payment: Payment) {
        val cancelId = pg.cancel(payment.orderId)
        val now = clock.instant()
        tx.executeWithoutResult {
            if (payments.markRefunded(payment.orderId, cancelId, now)) {
                outbox.publish(
                    Topics.PAYMENT_EVENTS,
                    payment.orderId.toString(),
                    PaymentRefunded(
                        payment.id.toString(),
                        payment.orderId.toString(),
                        payment.storeId,
                        payment.brandId,
                        payment.amount,
                        cancelId,
                        now,
                    ),
                )
                count("refunded")
            }
        }
    }

    private fun count(result: String) = meterRegistry.counter("brewslot.payment.result", "result", result).increment()

    companion object {
        const val CONSUMER = "payment-commands"
    }
}
