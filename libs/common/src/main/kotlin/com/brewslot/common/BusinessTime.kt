package com.brewslot.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 영업일(business date)은 한국 시간 기준 달력 날짜다.
 * 서버/DB 는 UTC(Instant) 로 저장하고, 날짜 경계가 의미를 가지는 정산·통계에서만 이 규칙으로 변환한다.
 */
object BusinessTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun businessDateOf(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    fun startOf(date: LocalDate): Instant = date.atStartOfDay(ZONE).toInstant()

    fun endExclusiveOf(date: LocalDate): Instant = date.plusDays(1).atStartOfDay(ZONE).toInstant()
}
