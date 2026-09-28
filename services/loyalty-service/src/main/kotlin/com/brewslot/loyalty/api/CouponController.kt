package com.brewslot.loyalty.api

import com.brewslot.loyalty.application.Campaign
import com.brewslot.loyalty.application.CouponRepository
import com.brewslot.loyalty.application.CouponService
import com.brewslot.loyalty.domain.Coupon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

data class CampaignRequest(
    val brandId: Long,
    @field:NotBlank val name: String,
    @field:Positive val discountAmount: Long,
    @field:PositiveOrZero val minOrderAmount: Long = 0,
    @field:Positive val issueLimit: Int,
    @field:Positive val validDays: Int = 14,
    val startsAt: Instant? = null,
    val endsAt: Instant,
)

data class CampaignResponse(
    val campaignId: Long,
    val brandId: Long,
    val name: String,
    val discountAmount: Long,
    val minOrderAmount: Long,
    val issueLimit: Int,
    val remaining: Int,
    val endsAt: Instant,
) {
    companion object {
        fun of(c: Campaign) =
            CampaignResponse(c.id, c.brandId, c.name, c.discountAmount, c.minOrderAmount, c.issueLimit, c.issueLimit - c.issuedCount, c.endsAt)
    }
}

data class CouponResponse(
    val couponId: String,
    val campaignId: Long,
    val brandId: Long,
    val discountAmount: Long,
    val minOrderAmount: Long,
    val status: String,
    val expiresAt: Instant,
    val usedOrderId: String?,
) {
    companion object {
        fun of(c: Coupon, now: Instant) = CouponResponse(
            c.id.toString(), c.campaignId, c.brandId, c.discountAmount, c.minOrderAmount,
            c.effectiveStatus(now).name, c.expiresAt, c.usedOrderId?.toString(),
        )
    }
}

@Tag(name = "Promotions")
@RestController
class CouponController(private val service: CouponService, private val coupons: CouponRepository, private val clock: Clock) {
    @Operation(summary = "[운영] 쿠폰 이벤트(캠페인) 생성 — 선착순 발급 한도 포함")
    @PostMapping("/admin/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody r: CampaignRequest,
    ): CampaignResponse =
        CampaignResponse.of(service.createCampaign(r.brandId, r.name, r.discountAmount, r.minOrderAmount, r.issueLimit, r.validDays, r.startsAt, r.endsAt))

    @Operation(summary = "진행 중인 브랜드 쿠폰 이벤트와 남은 수량")
    @GetMapping("/promotions")
    fun active(
        @RequestParam brandId: Long,
    ): List<CampaignResponse> = coupons.activeCampaigns(brandId, clock.instant()).map(CampaignResponse::of)

    @Operation(summary = "쿠폰 받기 (선착순). 이미 받았으면 같은 쿠폰을 200 으로 돌려준다")
    @PostMapping("/promotions/{campaignId}/claims")
    fun claim(
        @RequestHeader("X-Member-Id") memberId: Long,
        @PathVariable campaignId: Long,
    ): ResponseEntity<CouponResponse> {
        val result = service.claim(campaignId, memberId)
        return ResponseEntity.status(if (result.newlyIssued) HttpStatus.CREATED else HttpStatus.OK)
            .body(CouponResponse.of(result.coupon, clock.instant()))
    }

    @Operation(summary = "회원 쿠폰함. 주문 시 couponId 와 discountAmount 를 그대로 보내면 결제 단계에서 검증된다")
    @GetMapping("/members/{memberId}/coupons")
    fun mine(
        @PathVariable memberId: Long,
        @RequestParam(required = false) brandId: Long?,
    ): List<CouponResponse> {
        val now = clock.instant()
        return coupons.ofMember(memberId, brandId).map { CouponResponse.of(it, now) }
    }
}
