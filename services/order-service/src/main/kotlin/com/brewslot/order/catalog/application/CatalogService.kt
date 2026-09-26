package com.brewslot.order.catalog.application

import com.brewslot.order.catalog.domain.Station
import com.brewslot.order.catalog.domain.Store
import com.brewslot.order.catalog.infra.JdbcStoreRepository
import com.brewslot.web.NotFoundException
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 매장/메뉴는 "읽기 >>> 쓰기" 이고 크기가 작다. 주문·가용시간 조회마다 DB 를 치지 않도록
 * 인스턴스 로컬 캐시(Caffeine, TTL 30s)를 둔다. Redis 가 아닌 로컬 캐시를 고른 이유는 ADR-0004 참고.
 */
@Service
class CatalogService(private val stores: JdbcStoreRepository, private val clock: java.time.Clock) {
    @Cacheable(cacheNames = [CACHE_STORE], key = "#storeId")
    fun store(storeId: Long): Store = stores.findById(storeId) ?: throw NotFoundException("store", storeId)

    @Transactional
    @CacheEvict(cacheNames = [CACHE_STORE], key = "#storeId")
    fun updateStationCapacity(storeId: Long, unitsPerSlot: Map<Station, Int>): Store {
        stores.findById(storeId) ?: throw NotFoundException("store", storeId)
        stores.updateStationCapacity(storeId, unitsPerSlot, clock.instant())
        return stores.findById(storeId)!!
    }

    companion object {
        const val CACHE_STORE = "store"
    }
}
