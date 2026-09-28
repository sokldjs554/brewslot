package com.brewslot.order.ordering.domain

import java.time.Instant
import java.util.UUID

enum class TransferStatus {
    /** 새 매장 자리를 잡아 두고 새 매장의 수락을 기다린다. 원래 주문은 원래 매장에서 그대로 유효하다. */
    AWAITING_STORE,

    /** 새 매장이 수락해 주문이 옮겨졌다. */
    COMPLETED,

    /** 새 매장이 거절했다. 원래 주문 유지. */
    REFUSED,

    /** 새 매장이 기한 안에 답하지 않았다. 원래 주문 유지. */
    EXPIRED,

    /** 수락 전에 주문 쪽 상황이 바뀌었다(원래 매장이 먼저 제조 시작, 고객 취소 등). 원래 주문 유지. */
    ABORTED,
    ;

    val isOpen: Boolean get() = this == AWAITING_STORE
}

enum class TransferFailReason {
    REFUSED_BY_STORE,
    STORE_DID_NOT_RESPOND,
    ORIGINAL_STORE_STARTED,
    ORDER_CANCELLED,
}

class TransferClosedException(val status: TransferStatus) :
    RuntimeException("이미 끝난 매장 변경 요청입니다 ($status).")

/**
 * 매장 변경 요청. 원래 주문을 먼저 취소하지 않는다 — 새 매장이 수락하는 순간 한 트랜잭션에서 옮기고,
 * 그 전에는 어떤 결과(거절 · 무응답 · 원래 매장 제조 시작)가 나와도 원래 주문이 그대로 남는다.
 */
class StoreTransfer private constructor(
    val id: UUID,
    val orderId: UUID,
    val memberId: Long,
    val fromStoreId: Long,
    val toStoreId: Long,
    val previousPickupAt: Instant,
    val pickupAt: Instant,
    val newLines: List<OrderLine>,
    val requestedAt: Instant,
    val deadlineAt: Instant,
    status: TransferStatus,
    failReason: TransferFailReason?,
    decidedAt: Instant?,
) {
    var status: TransferStatus = status
        private set
    var failReason: TransferFailReason? = failReason
        private set
    var decidedAt: Instant? = decidedAt
        private set

    fun isOverdue(now: Instant): Boolean = status.isOpen && !now.isBefore(deadlineAt)

    fun complete(now: Instant) = close(TransferStatus.COMPLETED, null, now)

    fun refuse(now: Instant) = close(TransferStatus.REFUSED, TransferFailReason.REFUSED_BY_STORE, now)

    fun expire(now: Instant) = close(TransferStatus.EXPIRED, TransferFailReason.STORE_DID_NOT_RESPOND, now)

    fun abort(reason: TransferFailReason, now: Instant) = close(TransferStatus.ABORTED, reason, now)

    private fun close(to: TransferStatus, reason: TransferFailReason?, now: Instant) {
        if (!status.isOpen) throw TransferClosedException(status)
        status = to
        failReason = reason
        decidedAt = now
    }

    companion object {
        fun request(
            id: UUID,
            order: Order,
            toStoreId: Long,
            pickupAt: Instant,
            newLines: List<OrderLine>,
            now: Instant,
            deadlineAt: Instant,
        ): StoreTransfer {
            if (order.status != OrderStatus.PAID) throw IllegalOrderTransitionException(order.status, "매장 변경")
            require(toStoreId != order.storeId) { "같은 매장으로는 변경할 수 없습니다." }
            require(deadlineAt.isAfter(now)) { "수락 기한은 요청 시각 이후여야 합니다." }
            return StoreTransfer(
                id, order.id, order.memberId, order.storeId, toStoreId, order.promisedPickupAt, pickupAt, newLines,
                now, deadlineAt, TransferStatus.AWAITING_STORE, null, null,
            )
        }

        fun reconstitute(
            id: UUID,
            orderId: UUID,
            memberId: Long,
            fromStoreId: Long,
            toStoreId: Long,
            previousPickupAt: Instant,
            pickupAt: Instant,
            newLines: List<OrderLine>,
            requestedAt: Instant,
            deadlineAt: Instant,
            status: TransferStatus,
            failReason: TransferFailReason?,
            decidedAt: Instant?,
        ) = StoreTransfer(
            id, orderId, memberId, fromStoreId, toStoreId, previousPickupAt, pickupAt, newLines,
            requestedAt, deadlineAt, status, failReason, decidedAt,
        )
    }
}
