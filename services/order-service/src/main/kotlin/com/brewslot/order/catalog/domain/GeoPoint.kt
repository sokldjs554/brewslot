package com.brewslot.order.catalog.domain

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 매장 위치. 매장 변경 · 다른 매장 제안은 "원래 매장에서 걸어서 몇 분" 을 기준으로 한다. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** 직선거리(m) */
    fun metersTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** 도보 시간(분, 올림). 도심 골목 우회를 1.3배로 보고 분당 67m(시속 4km)로 걷는다고 가정한다. */
    fun walkMinutesTo(other: GeoPoint): Int = ceil(metersTo(other) * DETOUR / WALK_M_PER_MIN).toInt()

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val DETOUR = 1.3
        private const val WALK_M_PER_MIN = 67.0
    }
}
