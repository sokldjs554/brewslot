package com.brewslot.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class Uuid7Test {
    @Test
    fun `버전과 variant 비트가 RFC 9562 를 따른다`() {
        val id = Uuid7.next()
        assertThat(id.version()).isEqualTo(7)
        assertThat(id.variant()).isEqualTo(2)
    }

    @Test
    fun `시간이 증가하면 문자열 정렬 순서도 증가한다`() {
        val earlier = Uuid7.next(Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC))
        val later = Uuid7.next(Clock.fixed(Instant.parse("2026-09-01T00:00:00.001Z"), ZoneOffset.UTC))
        assertThat(earlier.toString()).isLessThan(later.toString())
    }
}
