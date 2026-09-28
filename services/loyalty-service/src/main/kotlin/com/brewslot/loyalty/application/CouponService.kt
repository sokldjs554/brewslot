package com.brewslot.loyalty.application

import com.brewslot.loyalty.domain.Coupon
import com.brewslot.web.ConflictException
import com.brewslot.web.NotFoundException
import com.brewslot.web.UnprocessableException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class ClaimResult(val coupon: Coupon, val newlyIssued: Boolean)

/**
 * 프로모션 캠페인과 쿠폰 발급.
 *
 * 선착순 발급은 "쿠폰 행 INSERT(회원당 1장) → 캠페인 재고 조건부 UPDATE" 를 한 트랜잭션으로 묶는다.
 * 재고가 없으면 예외로 롤백되어 쿠폰 행도 남지 않는다. 같은 회원의 재요청은 이미 받은 쿠폰을 그대로 돌려준다(멱등).
 */
@Service
class CouponService(
    private val coupons: CouponRepository,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val issued = meterRegistry.counter("brewslot.coupon.claims", "result", "issued")
    private val soldOut = meterRegistry.counter("brewslot.coupon.claims", "result", "sold_out")

    @Transactional
    fun createCampaign(
        brandId: Long,
        name: String,
        discount: Long,
        minOrder: Long,
        limit: Int,
        validDays: Int,
        startsAt: Instant?,
        endsAt: Instant,
    ): Campaign {
        val now = clock.instant()
        val id = coupons.insertCampaign(brandId, name, discount, minOrder, limit, validDays, startsAt ?: now, endsAt, now)
        return coupons.campaign(id)!!
    }

    @Transactional
    fun claim(campaignId: Long, memberId: Long): ClaimResult {
        val now = clock.instant()
        val campaign = coupons.campaign(campaignId) ?: throw NotFoundException("campaign", campaignId)
        if (now.isBefore(campaign.startsAt) || !now.isBefore(campaign.endsAt)) {
            throw UnprocessableException("campaign-not-active", "진행 중인 이벤트가 아닙니다.")
        }
        val newId = coupons.insertIfAbsent(campaign, memberId, now)
            ?: return ClaimResult(coupons.find(campaignId, memberId)!!, newlyIssued = false)
        if (!coupons.takeStock(campaignId, now)) {
            soldOut.increment()
            throw ConflictException("campaign-sold-out", "선착순 쿠폰이 모두 소진됐습니다.", mapOf("issueLimit" to campaign.issueLimit))
        }
        issued.increment()
        return ClaimResult(coupons.find(campaignId, memberId)!!.also { check(it.id == newId) }, newlyIssued = true)
    }
}
