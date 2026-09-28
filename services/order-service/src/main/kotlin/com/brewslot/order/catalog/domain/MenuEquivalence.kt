package com.brewslot.order.catalog.domain

import com.brewslot.common.Money

/** 다른 매장으로 옮길 때 기준이 되는 "고객이 고른 음료". 메뉴 ID 는 매장마다 다르므로 이름·가격으로 대조한다. */
data class ChosenDrink(val name: String, val unitPrice: Money, val quantity: Int)

sealed interface MenuMatch {
    /** 모든 음료가 같은 이름 · 같은 가격으로 판매 중이다. [items] 는 대상 매장의 메뉴와 수량 (입력 순서 유지) */
    data class Compatible(val items: List<Pair<MenuItem, Int>>) : MenuMatch

    /** 옮길 수 없는 이유. 고객 화면에 음료 이름으로 그대로 보여준다. */
    data class Incompatible(
        val notSold: List<String>,
        val soldOut: List<String>,
        val priceDiffers: List<String>,
    ) : MenuMatch {
        val reason: String
            get() = when {
                notSold.isNotEmpty() -> "NOT_SOLD"
                soldOut.isNotEmpty() -> "SOLD_OUT"
                else -> "PRICE_DIFFERS"
            }
    }
}

/**
 * 같은 브랜드의 다른 매장에서 "같은 음료" 를 만들 수 있는지 판단한다 (순수 함수).
 *
 * - 같은 이름의 메뉴가 있어야 한다. 매장마다 메뉴 ID · 스테이션 · 제조 부하가 다를 수 있으므로 제조 계획은 대상 매장 메뉴로 다시 세운다.
 * - 가격이 같아야 한다. 결제 금액(카드 · 포인트 · 쿠폰)을 바꾸지 않고 옮기기 위해서다 — 차액 결제·부분 환불은 범위 밖(ADR-0013).
 * - 품절이면 옮길 수 없다.
 */
object MenuEquivalence {
    fun match(drinks: List<ChosenDrink>, target: Store): MenuMatch {
        val byName = target.menu.associateBy { it.name }
        val notSold = mutableListOf<String>()
        val soldOut = mutableListOf<String>()
        val priceDiffers = mutableListOf<String>()
        val matched = drinks.mapNotNull { d ->
            val m = byName[d.name]
            when {
                m == null -> null.also { notSold += d.name }
                !m.available -> null.also { soldOut += d.name }
                m.price != d.unitPrice -> null.also { priceDiffers += d.name }
                else -> m to d.quantity
            }
        }
        return if (notSold.isEmpty() && soldOut.isEmpty() && priceDiffers.isEmpty()) {
            MenuMatch.Compatible(matched)
        } else {
            MenuMatch.Incompatible(notSold, soldOut, priceDiffers)
        }
    }
}
