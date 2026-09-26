package com.brewslot.payment

import com.brewslot.messaging.EnvelopeCodec
import com.brewslot.messaging.EventEnvelope
import com.brewslot.messaging.contract.ChargePayment
import com.brewslot.messaging.contract.RefundPayment
import com.brewslot.payment.application.JdbcPaymentRepository
import com.brewslot.payment.application.PaymentService
import com.brewslot.payment.domain.PaymentStatus
import com.brewslot.payment.fakepg.FakePgClient
import com.brewslot.testsupport.Containers
import com.brewslot.testsupport.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(properties = ["spring.config.name=payment-service", "brewslot.payment.recovery-poll-ms=3600000"])
@Import(PaymentServiceIntegrationTest.ClockConfig::class)
class PaymentServiceIntegrationTest {
    @Autowired lateinit var service: PaymentService

    @Autowired lateinit var payments: JdbcPaymentRepository

    @Autowired lateinit var fakePg: FakePgClient

    @Autowired lateinit var codec: EnvelopeCodec

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var clock: MutableClock

    private fun envelope(orderId: UUID, payload: Any) = EventEnvelope(
        UUID.randomUUID(), payload::class.java.simpleName, orderId.toString(), clock.instant(), orderId.toString(), null, codec.mapper.valueToTree(payload),
    )

    private fun charge(orderId: UUID, token: String, amount: Long = 9_000) =
        ChargePayment(orderId.toString(), 1, 101, 1, amount, token).let { service.charge(envelope(orderId, it), it) }

    private fun refund(orderId: UUID) = RefundPayment(orderId.toString(), "TEST").let { service.refund(envelope(orderId, it), it) }

    private fun emitted(orderId: UUID): List<String> =
        jdbc.sql("SELECT event_type FROM outbox_event WHERE message_key = :k ORDER BY id").param("k", orderId.toString()).query(String::class.java).list()

    @Test
    fun `승인되면 CAPTURED 와 PaymentCaptured 를 남기고, 같은 명령이 다시 와도 PG 를 두 번 부르지 않는다`() {
        val orderId = UUID.randomUUID()
        charge(orderId, "tok_visa")
        charge(orderId, "tok_visa")

        assertThat(payments.findByOrderId(orderId)!!.status).isEqualTo(PaymentStatus.CAPTURED)
        assertThat(emitted(orderId)).containsExactly("PaymentCaptured")
        assertThat(jdbc.sql("SELECT count(*) FROM fake_pg_transaction WHERE order_id = :id").param("id", orderId).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `거절되면 FAILED 와 PaymentFailed`() {
        val orderId = UUID.randomUUID()
        charge(orderId, "tok_declined")
        assertThat(payments.findByOrderId(orderId)!!.failureReason).isEqualTo("CARD_DECLINED")
        assertThat(emitted(orderId)).containsExactly("PaymentFailed")
    }

    @Test
    fun `PG 타임아웃 - 결과를 모르는 상태에서 온 환불 명령은 예약되고, 복구 시 승인 확인 후 자동 취소된다`() {
        val orderId = UUID.randomUUID()
        charge(orderId, "tok_timeout")
        assertThat(payments.findByOrderId(orderId)!!.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(emitted(orderId)).isEmpty()

        refund(orderId) // Saga 타임아웃이 보낸 보상 명령
        assertThat(payments.findByOrderId(orderId)!!.refundRequested).isTrue()

        clock.advance(Duration.ofSeconds(30))
        service.recoverUnresolved()

        val payment = payments.findByOrderId(orderId)!!
        assertThat(payment.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(emitted(orderId)).containsExactly("PaymentCaptured", "PaymentRefunded")
        assertThat(fakePg.lookup(orderId)!!.cancelId).isNotNull()
    }

    @Test
    fun `결제가 없던 주문의 환불 명령은 아무 일도 하지 않는다`() {
        val orderId = UUID.randomUUID()
        refund(orderId)
        assertThat(payments.findByOrderId(orderId)).isNull()
        assertThat(emitted(orderId)).isEmpty()
    }

    @Test
    fun `PG 정산 파일에는 승인과 취소가 수수료(2_2퍼센트, 반올림)와 함께 거래일 기준으로 담긴다`() {
        clock.set(Instant.parse("2026-10-20T01:00:00Z"))
        val orderId = UUID.randomUUID()
        charge(orderId, "tok_visa", amount = 10_050)
        refund(orderId)

        val lines = fakePg.settlementFile(LocalDate.parse("2026-10-20")).filter { it.orderId == orderId.toString() }
        assertThat(lines.map { Triple(it.kind, it.amount, it.fee) })
            .containsExactly(Triple("APPROVE", 10_050L, 221L), Triple("CANCEL", -10_050L, -221L))
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        @Primary
        fun clock() = MutableClock(Instant.parse("2026-10-19T23:00:00Z"))
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Containers.registerPostgres(registry, "payment_db")
            Containers.registerKafka(registry)
        }
    }
}
