package com.nihyli.cloverpromotions.engine

import com.nihyli.cloverpromotions.data.PromoRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoCalculatorTest {

    private val try5 = "item-try5"

    private val fiveForFour = PromoRule(
        id = 1,
        name = "5 x Try 5 for $4.00",
        itemId = try5,
        itemName = "Try 5",
        requiredQty = 5,
        bundlePriceCents = 400,
    )
    private val tenForSeven = PromoRule(
        id = 2,
        name = "10 x Try 5 for $7.00",
        itemId = try5,
        itemName = "Try 5",
        requiredQty = 10,
        bundlePriceCents = 700,
    )
    private val rules = listOf(fiveForFour, tenForSeven)

    @Test
    fun tenUnitsUsesOnlyTheTenPack_notBothDeals() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 10)))
        assertEquals(1, discounts.size)
        assertEquals("PROMO: 10 x Try 5 for $7.00", discounts.single().name)
        // 10 x $1.00 = $10.00, 10-for-$7 saves $3.00
        assertEquals(-300L, discounts.single().amountCents)
    }

    @Test
    fun fiveUnitsUsesOnlyTheFivePack() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 5)))
        assertEquals(1, discounts.size)
        assertEquals("PROMO: 5 x Try 5 for $4.00", discounts.single().name)
        assertEquals(-100L, discounts.single().amountCents)
    }

    @Test
    fun fifteenUnitsUsesTenPackPlusLeftoverFivePack() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 15)))
        val byName = discounts.groupBy { it.name }
        assertEquals(setOf("PROMO: 10 x Try 5 for $7.00", "PROMO: 5 x Try 5 for $4.00"), byName.keys)
        assertEquals(-300L, byName.getValue("PROMO: 10 x Try 5 for $7.00").sumOf { it.amountCents })
        assertEquals(-100L, byName.getValue("PROMO: 5 x Try 5 for $4.00").sumOf { it.amountCents })
        assertEquals(-400L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun twelveUnitsUsesTenPackNotTwoFivePacks() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 12)))
        assertEquals(listOf("PROMO: 10 x Try 5 for $7.00"), discounts.map { it.name }.distinct())
        assertEquals(-300L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun twentyUnitsUsesTwoTenPacks() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 20)))
        assertEquals(listOf("PROMO: 10 x Try 5 for $7.00"), discounts.map { it.name }.distinct())
        assertEquals(-600L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun fourUnitsGetsNoPromo() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 4)))
        assertTrue(discounts.isEmpty())
    }

    @Test
    fun differentItemsCanEachGetTheirOwnPromo() {
        val redbull = PromoRule(
            id = 3,
            name = "2 x Redbull for $5.00",
            itemId = "item-rb",
            itemName = "Redbull",
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            rules + redbull,
            listOf(
                line(qty = 10),
                CartLine("li-rb", "item-rb", "Redbull", unitPriceCents = 300, quantity = 2),
            ),
        )
        assertEquals(2, discounts.size)
        assertTrue(discounts.any { it.name.contains("10 x Try 5") })
        assertTrue(discounts.any { it.name.contains("2 x Redbull") })
        assertEquals(-100L, discounts.single { it.name.contains("Redbull") }.amountCents)
    }

    @Test
    fun bundlePricedAboveRetailIsSkipped() {
        val expensive = fiveForFour.copy(bundlePriceCents = 10_000)
        val discounts = PromoCalculator.desiredDiscounts(listOf(expensive), listOf(line(qty = 5)))
        assertTrue(discounts.isEmpty())
    }

    @Test
    fun inactiveRuleIsIgnored() {
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(fiveForFour.copy(active = false), tenForSeven),
            listOf(line(qty = 10)),
        )
        assertEquals(listOf("PROMO: 10 x Try 5 for $7.00"), discounts.map { it.name })
    }

    @Test
    fun splitLinesSameItemStillPickOneDeal() {
        val discounts = PromoCalculator.desiredDiscounts(
            rules,
            listOf(
                line(id = "a", qty = 6),
                line(id = "b", qty = 4),
            ),
        )
        assertEquals("PROMO: 10 x Try 5 for $7.00", discounts.distinctBy { it.name }.single().name)
        assertEquals(-300L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun tenDollarDiscountDoesNotAllLandOnAOneDollarLine() {
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(tenForSeven),
            listOf(
                line(id = "nine", qty = 9),
                line(id = "one", qty = 1),
            ),
        )
        assertEquals(-300L, discounts.sumOf { it.amountCents })
        assertEquals(-270L, discounts.single { it.lineItemId == "nine" }.amountCents)
        assertEquals(-30L, discounts.single { it.lineItemId == "one" }.amountCents)
    }

    @Test
    fun tenSeparateDollarLinesGetTheSameDiscountSoRegisterCanStackThem() {
        val lines = (1..10).map { line(id = "l$it", qty = 1) }
        val discounts = PromoCalculator.desiredDiscounts(listOf(tenForSeven), lines)
        assertEquals(10, discounts.size)
        assertTrue(discounts.all { it.amountCents == -30L })
        assertEquals(-300L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun twoRedbullLinesGetMatchingDiscounts() {
        val redbull = PromoRule(
            id = 3,
            name = "2 x Redbull for $5.00",
            itemId = "item-rb",
            itemName = "Redbull",
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(redbull),
            listOf(
                CartLine("a", "item-rb", "Redbull", 300, 1),
                CartLine("b", "item-rb", "Redbull", 300, 1),
            ),
        )
        assertEquals(2, discounts.size)
        assertTrue(discounts.all { it.amountCents == -50L })
    }

    @Test
    fun knownItemIdDoesNotMatchOnNameAlone() {
        val other = CartLine("li", "item-other", "Try 5", unitPriceCents = 100, quantity = 10)
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(other))
        assertTrue(discounts.isEmpty())
    }

    @Test
    fun oneRedbullHintsToAddOneMore() {
        val redbull = PromoRule(
            id = 3,
            name = "2 x Redbull for $5.00",
            itemId = "item-rb",
            itemName = "Redbull",
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        val nudges = PromoCalculator.desiredNudges(
            listOf(redbull),
            listOf(CartLine("a", "item-rb", "Redbull", 300, 1)),
        )
        assertEquals(1, nudges.size)
        assertEquals("Add 1 more Redbull to get $1.00 off", nudges.single().message)
        assertEquals(setOf("a"), nudges.single().lineItemIds)
    }

    @Test
    fun eightTry5HintsToAddTwoForTenPack() {
        val nudges = PromoCalculator.desiredNudges(rules, listOf(line(qty = 8)))
        assertEquals("Add 2 more Try 5 to get $2.00 off", nudges.single().message)
    }

    @Test
    fun fourTry5HintsToAddOneForFivePack() {
        val nudges = PromoCalculator.desiredNudges(rules, listOf(line(qty = 4)))
        assertEquals("Add 1 more Try 5 to get $1.00 off", nudges.single().message)
    }

    @Test
    fun tenTry5HintsToAddFiveForNextFivePack() {
        val nudges = PromoCalculator.desiredNudges(rules, listOf(line(qty = 10)))
        assertEquals("Add 5 more Try 5 to get $1.00 off", nudges.single().message)
    }

    @Test
    fun hintNoteMatcher() {
        assertTrue(PromoCalculator.isHintNote("Add 1 more Redbull to get $1.00 off"))
        assertTrue(!PromoCalculator.isHintNote("extra shot"))
        assertTrue(!PromoCalculator.isHintNote(null))
    }

    private fun line(id: String = "li-try5", qty: Int) = CartLine(
        lineItemId = id,
        itemId = try5,
        itemName = "Try 5",
        unitPriceCents = 100,
        quantity = qty,
    )
}
