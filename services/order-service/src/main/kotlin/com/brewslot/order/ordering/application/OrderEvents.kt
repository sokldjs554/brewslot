package com.brewslot.order.ordering.application

import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.OrderCancelled
import com.brewslot.messaging.contract.OrderPaid
import com.brewslot.messaging.contract.OrderPickedUp
import com.brewslot.messaging.contract.OrderPlaced
import com.brewslot.messaging.contract.OrderPreparing
import com.brewslot.messaging.contract.OrderReady
import com.brewslot.messaging.contract.OrderedItem
import com.brewslot.messaging.outbox.Outbox
import com.brewslot.order.ordering.domain.Order
import org.springframework.stereotype.Component

/** 도메인 상태 → 공개 계약(Published Language) 변환 후 Outbox 적재. */
@Component
class OrderEvents(private val outbox: Outbox) {
    fun placed(o: Order) = publish(
        o,
        OrderPlaced(o.id.toString(), o.memberId, o.storeId, o.brandId, o.totalAmount.won, o.promisedPickupAt, items(o), o.createdAt),
    )

    fun paid(o: Order) = publish(
        o,
        OrderPaid(
            o.id.toString(),
            o.memberId,
            o.storeId,
            o.brandId,
            o.totalAmount.won,
            o.cardAmount.won,
            o.pointAmount.won,
            o.promisedPickupAt,
            items(o),
            o.paidAt!!,
            o.couponAmount.won,
        ),
    )

    fun preparing(o: Order) = publish(o, OrderPreparing(o.id.toString(), o.storeId, o.preparingAt!!, o.memberId))

    fun ready(o: Order) = publish(o, OrderReady(o.id.toString(), o.memberId, o.storeId, o.promisedPickupAt, o.readyAt!!))

    fun pickedUp(o: Order) = publish(
        o,
        OrderPickedUp(
            o.id.toString(),
            o.memberId,
            o.storeId,
            o.brandId,
            o.totalAmount.won,
            o.cardAmount.won,
            o.pointAmount.won,
            o.promisedPickupAt,
            o.readyAt!!,
            o.pickedUpAt!!,
            o.couponAmount.won,
        ),
    )

    fun cancelled(o: Order) = publish(
        o,
        OrderCancelled(o.id.toString(), o.memberId, o.storeId, o.brandId, o.cancelReason!!.name, o.wasPaid, o.cancelledAt!!),
    )

    private fun items(o: Order) = o.lines.map { OrderedItem(it.menuItemId, it.name, it.quantity, it.unitPrice.won) }

    private fun publish(o: Order, payload: Any) {
        outbox.publish(Topics.ORDER_EVENTS, o.id.toString(), payload)
    }
}
