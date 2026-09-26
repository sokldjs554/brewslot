package com.brewslot.common

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * RFC 9562 UUIDv7 (앞 48bit 가 밀리초 타임스탬프).
 *
 * 주문 PK 로 랜덤 UUIDv4 를 쓰면 B-Tree 삽입 위치가 무작위라 페이지 분할과 캐시 미스가 늘어난다.
 * v7 은 시간순으로 증가하므로 인덱스 우측 끝에만 삽입되어 쓰기 지역성이 좋고,
 * 전역 유일성(서비스 간 ID 충돌 없음)도 그대로 유지된다.
 */
object Uuid7 {
    private val random = SecureRandom()

    fun next(clock: Clock = Clock.systemUTC()): UUID {
        val millis = clock.millis()
        val randA = random.nextInt(1 shl 12).toLong()
        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL
        val lsb = randB or Long.MIN_VALUE // variant 10xx
        return UUID(msb, lsb)
    }
}
