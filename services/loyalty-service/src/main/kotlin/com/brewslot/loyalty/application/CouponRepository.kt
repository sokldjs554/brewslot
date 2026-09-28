package com.brewslot.loyalty.application

import com.brewslot.loyalty.domain.Coupon
import com.brewslot.loyalty.domain.CouponStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class Campaign(
    val id: Long,
    val brandId: Long,
    val name: String,
    val discountAmount: Long,
    val minOrderAmount: Long,
    val issueLimit: Int,
    val issuedCount: Int,
    val validDays: Int,
    val startsAt: Instant,
    val endsAt: Instant,
)

@Repository
class CouponRepository(private val jdbc: JdbcClient) {
    fun insertCampaign(
        brandId: Long,
        name: String,
        discount: Long,
        minOrder: Long,
        limit: Int,
        validDays: Int,
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): Long = jdbc.sql(
        """
        INSERT INTO coupon_campaign (brand_id, name, discount_amount, min_order_amount, issue_limit, valid_days, starts_at, ends_at, created_at)
        VALUES (:b, :name, :d, :min, :limit, :days, :starts, :ends, :now) RETURNING id
        """.trimIndent(),
    )
        .param("b", brandId).param("name", name).param("d", discount).param("min", minOrder).param("limit", limit)
        .param("days", validDays).param("starts", Timestamp.from(startsAt)).param("ends", Timestamp.from(endsAt))
        .param("now", Timestamp.from(now))
        .query(Long::class.java).single()

    fun campaign(id: Long): Campaign? = jdbc.sql("SELECT * FROM coupon_campaign WHERE id = :id")
        .param("id", id).query { rs, _ -> rs.toCampaign() }.optional().orElse(null)

    fun activeCampaigns(brandId: Long, now: Instant): List<Campaign> = jdbc.sql(
        "SELECT * FROM coupon_campaign WHERE brand_id = :b AND starts_at <= :now AND ends_at > :now ORDER BY id",
    ).param("b", brandId).param("now", Timestamp.from(now)).query { rs, _ -> rs.toCampaign() }.list()

    /** 캠페인당 회원 1장. 이미 받았으면 null (새로 만들지 않음). */
    fun insertIfAbsent(c: Campaign, memberId: Long, now: Instant): UUID? = jdbc.sql(
        """
        INSERT INTO member_coupon (id, campaign_id, member_id, brand_id, discount_amount, min_order_amount, status, issued_at, expires_at)
        VALUES (:id, :campaign, :m, :b, :d, :min, 'ISSUED', :now, :expires)
        ON CONFLICT (campaign_id, member_id) DO NOTHING
        RETURNING id
        """.trimIndent(),
    )
        .param("id", UUID.randomUUID()).param("campaign", c.id).param("m", memberId).param("b", c.brandId)
        .param("d", c.discountAmount).param("min", c.minOrderAmount).param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(now.plusSeconds(c.validDays * 86_400L)))
        .query(UUID::class.java).optional().orElse(null)

    /**
     * 선착순 한도 차감. 조건부 UPDATE 한 문장이라 동시 요청이 몰려도 issue_limit 을 넘지 않는다
     * (행 잠금으로 직렬화되고, 조건을 다시 평가한다). 반환: 성공 여부.
     */
    fun takeStock(campaignId: Long, now: Instant): Boolean = jdbc.sql(
        """
        UPDATE coupon_campaign SET issued_count = issued_count + 1
        WHERE id = :id AND issued_count < issue_limit AND starts_at <= :now AND ends_at > :now
        """.trimIndent(),
    ).param("id", campaignId).param("now", Timestamp.from(now)).update() == 1

    fun find(campaignId: Long, memberId: Long): Coupon? =
        jdbc.sql("SELECT * FROM member_coupon WHERE campaign_id = :c AND member_id = :m")
            .param("c", campaignId).param("m", memberId).query { rs, _ -> rs.toCoupon() }.optional().orElse(null)

    fun findForUpdate(id: UUID): Coupon? = jdbc.sql("SELECT * FROM member_coupon WHERE id = :id FOR UPDATE")
        .param("id", id).query { rs, _ -> rs.toCoupon() }.optional().orElse(null)

    fun findByOrderForUpdate(orderId: UUID): Coupon? = jdbc.sql("SELECT * FROM member_coupon WHERE used_order_id = :o FOR UPDATE")
        .param("o", orderId).query { rs, _ -> rs.toCoupon() }.optional().orElse(null)

    fun ofMember(memberId: Long, brandId: Long?): List<Coupon> = jdbc.sql(
        "SELECT * FROM member_coupon WHERE member_id = :m ${if (brandId != null) "AND brand_id = :b" else ""} ORDER BY expires_at, id",
    ).param("m", memberId).apply { if (brandId != null) param("b", brandId) }.query { rs, _ -> rs.toCoupon() }.list()

    fun markUsed(id: UUID, orderId: UUID, now: Instant) {
        jdbc.sql("UPDATE member_coupon SET status = 'USED', used_order_id = :o, used_at = :now WHERE id = :id")
            .param("o", orderId).param("now", Timestamp.from(now)).param("id", id).update()
    }

    fun restore(id: UUID, status: CouponStatus) {
        jdbc.sql("UPDATE member_coupon SET status = :s, used_order_id = NULL, used_at = NULL WHERE id = :id")
            .param("s", status.name).param("id", id).update()
    }

    private fun ResultSet.toCampaign() = Campaign(
        getLong("id"), getLong("brand_id"), getString("name"), getLong("discount_amount"), getLong("min_order_amount"),
        getInt("issue_limit"), getInt("issued_count"), getInt("valid_days"),
        getTimestamp("starts_at").toInstant(), getTimestamp("ends_at").toInstant(),
    )

    private fun ResultSet.toCoupon() = Coupon(
        id = getObject("id", UUID::class.java),
        campaignId = getLong("campaign_id"),
        memberId = getLong("member_id"),
        brandId = getLong("brand_id"),
        discountAmount = getLong("discount_amount"),
        minOrderAmount = getLong("min_order_amount"),
        status = CouponStatus.valueOf(getString("status")),
        expiresAt = getTimestamp("expires_at").toInstant(),
        usedOrderId = getObject("used_order_id", UUID::class.java),
    )
}
