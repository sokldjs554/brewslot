package com.brewslot.loyalty.application

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.Topics
import com.brewslot.messaging.contract.OrderPickedUp
import com.brewslot.messaging.contract.OrderTransferred
import com.brewslot.messaging.contract.RedeemPoints
import com.brewslot.messaging.contract.ReverseRedemption
import com.brewslot.messaging.inbox.Inbox
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class LoyaltyListeners(
    private val codec: EnvelopeCodec,
    private val inbox: Inbox,
    private val points: PointService,
) {
    @KafkaListener(topics = [Topics.LOYALTY_COMMANDS], groupId = "loyalty-service")
    fun onCommand(message: String) {
        val envelope = codec.decode(message)
        when (envelope.eventType) {
            RedeemPoints::class.simpleName -> codec.payloadOf<RedeemPoints>(envelope).let { c ->
                inbox.process(CONSUMER_COMMANDS, envelope) {
                    points.redeem(
                        UUID.fromString(c.orderId),
                        c.memberId,
                        c.brandId,
                        c.storeId,
                        c.amount,
                        c.couponId?.let(UUID::fromString),
                        c.couponAmount,
                        c.orderTotal,
                    )
                }
            }
            ReverseRedemption::class.simpleName -> codec.payloadOf<ReverseRedemption>(envelope).let { c ->
                inbox.process(CONSUMER_COMMANDS, envelope) { points.reverseRedemption(UUID.fromString(c.orderId), c.reason, c.storeId) }
            }
        }
    }

    /** 적립 시점 = 픽업 완료 (결제 직후 적립 후 취소 시 회수하는 복잡도를 피한다) */
    @KafkaListener(topics = [Topics.ORDER_EVENTS], groupId = "loyalty-earn")
    fun onOrderEvent(message: String) {
        val envelope = codec.decode(message)
        when (envelope.eventType) {
            OrderPickedUp::class.simpleName -> codec.payloadOf<OrderPickedUp>(envelope).let { e ->
                inbox.process(CONSUMER_EARN, envelope) { points.earn(UUID.fromString(e.orderId), e.memberId, e.brandId, e.cardAmount) }
            }
            // 매장 변경: 포인트 · 쿠폰 결제분의 매장 채권을 새 매장으로
            OrderTransferred::class.simpleName -> codec.payloadOf<OrderTransferred>(envelope).let { e ->
                inbox.process(CONSUMER_EARN, envelope) {
                    points.transferClearing(
                        UUID.fromString(e.orderId),
                        e.memberId,
                        e.brandId,
                        e.fromStoreId,
                        e.toStoreId,
                        e.pointAmount + e.couponAmount,
                    )
                }
            }
        }
    }

    companion object {
        const val CONSUMER_COMMANDS = "loyalty-commands"
        const val CONSUMER_EARN = "loyalty-earn"
    }
}

@Component
class PointExpiryJob(private val ledger: PointLedger, private val points: PointService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${brewslot.loyalty.expiry-cron:0 10 0 * * *}", zone = "Asia/Seoul")
    fun run(): Long {
        var total = 0L
        while (true) {
            val wallets = ledger.walletsWithExpiredLots(clock.instant(), 500)
            if (wallets.isEmpty()) break
            wallets.forEach { (m, b) -> total += points.expireWallet(m, b) }
        }
        if (total > 0) log.info("expired {} points", total)
        return total
    }
}
