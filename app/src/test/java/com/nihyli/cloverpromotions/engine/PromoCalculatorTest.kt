package com.nihyli.cloverpromotions.engine

import com.nihyli.cloverpromotions.data.BundlePriceMode
import com.nihyli.cloverpromotions.data.PromoItemRef
import com.nihyli.cloverpromotions.data.PromoRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PromoCalculatorTest {

    private val try5 = "item-try5"

    private val fiveForFour = PromoRule(
        id = 1,
        name = "5 x Try 5 for $4.00",
        label = "Try 5",
        items = listOf(PromoItemRef(try5, "Try 5")),
        requiredQty = 5,
        bundlePriceCents = 400,
    )
    private val tenForSeven = PromoRule(
        id = 2,
        name = "10 x Try 5 for $7.00",
        label = "Try 5",
        items = listOf(PromoItemRef(try5, "Try 5")),
        requiredQty = 10,
        bundlePriceCents = 700,
    )
    private val rules = listOf(fiveForFour, tenForSeven)

    private val twoRedbullForFive = PromoRule(
        id = 3,
        name = "2 x Redbull for $5.00",
        label = "Redbull",
        items = listOf(PromoItemRef("item-rb", "Redbull")),
        requiredQty = 2,
        bundlePriceCents = 500,
    )

    // A multi-flavor promotion: red + blue Red Bull count toward the same deal.
    private val redRb = "item-rb-red"
    private val blueRb = "item-rb-blue"
    private val redBullFlavors = PromoRule(
        id = 4,
        name = "2 x Red Bull for $5.00",
        label = "Red Bull",
        items = listOf(PromoItemRef(redRb, "Red Bull Red"), PromoItemRef(blueRb, "Red Bull Blue")),
        requiredQty = 2,
        bundlePriceCents = 500,
    )

    private fun rb(id: String, priceCents: Long = 300, qty: Int = 1) =
        CartLine(id, "item-rb", "Redbull", priceCents, qty)

    private fun flavor(lineId: String, itemId: String, name: String, priceCents: Long = 300, qty: Int = 1) =
        CartLine(lineId, itemId, name, priceCents, qty)

    @Test
    fun tenUnitsUsesOnlyTheTenPack_notBothDeals() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 10)))
        assertEquals(1, discounts.size)
        assertEquals("PROMO: 10 x Try 5 for $7.00", discounts.single().name)
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
        val discounts = PromoCalculator.desiredDiscounts(
            rules + twoRedbullForFive,
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
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(twoRedbullForFive),
            listOf(rb("a"), rb("b")),
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

    // ---- multiple items in one promotion ----

    @Test
    fun mixedFlavorsCountTowardOnePromo() {
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(redBullFlavors),
            listOf(
                flavor("red", redRb, "Red Bull Red"),
                flavor("blue", blueRb, "Red Bull Blue"),
            ),
        )
        // 1 red + 1 blue = 2 units -> the 2-for-$5 promo fires.
        assertEquals(2, discounts.size)
        assertEquals(-100L, discounts.sumOf { it.amountCents })
        assertTrue(discounts.all { it.name == "PROMO: 2 x Red Bull for $5.00" })
        assertTrue(discounts.all { it.amountCents == -50L })
    }

    @Test
    fun fiveMixedFlavorUnitsFillAFivePack() {
        val fiveForFourFlavors = redBullFlavors.copy(
            id = 5,
            name = "5 x Red Bull for $4.00",
            requiredQty = 5,
            bundlePriceCents = 400,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(fiveForFourFlavors),
            listOf(
                flavor("red", redRb, "Red Bull Red", priceCents = 100, qty = 3),
                flavor("blue", blueRb, "Red Bull Blue", priceCents = 100, qty = 2),
            ),
        )
        // 3 red + 2 blue = 5 units at $1.00 -> $5.00 retail, bundle $4.00.
        assertEquals(-100L, discounts.sumOf { it.amountCents })
        assertTrue(discounts.all { it.name == "PROMO: 5 x Red Bull for $4.00" })
    }

    @Test
    fun separatePromosDoNotCrossCount() {
        val redOnly = PromoRule(
            id = 6,
            name = "2 x Red Bull Red for $5.00",
            label = "Red Bull Red",
            items = listOf(PromoItemRef(redRb, "Red Bull Red")),
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        val blueOnly = PromoRule(
            id = 7,
            name = "2 x Red Bull Blue for $5.00",
            label = "Red Bull Blue",
            items = listOf(PromoItemRef(blueRb, "Red Bull Blue")),
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        // 1 red + 1 blue: neither single-flavor promo reaches 2, so no deal.
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(redOnly, blueOnly),
            listOf(
                flavor("red", redRb, "Red Bull Red"),
                flavor("blue", blueRb, "Red Bull Blue"),
            ),
        )
        assertTrue(discounts.isEmpty())
    }

    @Test
    fun overlappingItemIsClaimedByEarliestPromo() {
        // Both promos include red; the earlier (lower id) one wins the units.
        val groupPromo = redBullFlavors // id 4, {red, blue}
        val redOnlyLater = PromoRule(
            id = 8,
            name = "2 x Red Bull Red for $4.00",
            label = "Red Bull Red",
            items = listOf(PromoItemRef(redRb, "Red Bull Red")),
            requiredQty = 2,
            bundlePriceCents = 400,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(groupPromo, redOnlyLater),
            listOf(flavor("red", redRb, "Red Bull Red", qty = 2)),
        )
        assertTrue(discounts.all { it.name == "PROMO: 2 x Red Bull for $5.00" })
        assertEquals(-100L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun mixedFlavorNudgeUsesGroupLabel() {
        val nudges = PromoCalculator.desiredNudges(
            listOf(redBullFlavors),
            listOf(flavor("red", redRb, "Red Bull Red")),
        )
        assertEquals("Add 1 more Red Bull to get $1.00 off", nudges.single().message)
    }

    @Test
    fun pollutedStoredNameDoesNotAppearOnTheDiscount() {
        val candy = PromoRule(
            id = 10,
            name = "3 x 3 for 3 Candy for $3.00",
            label = "3 for 3 Candy",
            items = listOf(PromoItemRef("item-candy", "Candy")),
            requiredQty = 3,
            bundlePriceCents = 300,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(candy),
            listOf(CartLine("a", "item-candy", "Candy", 150, 3)),
        )
        assertEquals(listOf("PROMO: 3 x Candy for $3.00"), discounts.map { it.name }.distinct())
        val nudges = PromoCalculator.desiredNudges(
            listOf(candy),
            listOf(CartLine("a", "item-candy", "Candy", 150, 1)),
        )
        assertEquals("Add 2 more Candy to get $1.50 off", nudges.single().message)
    }

    // ---- nudges / notes ----

    @Test
    fun oneRedbullHintsToAddOneMore() {
        val nudges = PromoCalculator.desiredNudges(
            listOf(twoRedbullForFive),
            listOf(rb("a")),
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
    fun twoRedbullsShareEmptyNotesSoRegisterCanStack() {
        val notes = PromoCalculator.desiredNotes(
            listOf(twoRedbullForFive),
            listOf(rb("a"), rb("b")),
        )
        assertEquals("", notes["a"])
        assertEquals("", notes["b"])
        assertTrue(
            PromoCalculator.desiredNudges(
                listOf(twoRedbullForFive),
                listOf(rb("a"), rb("b")),
            ).isEmpty(),
        )
    }

    @Test
    fun completeTry5PacksHaveNoHint() {
        assertTrue(PromoCalculator.desiredNudges(rules, listOf(line(qty = 5))).isEmpty())
        assertTrue(PromoCalculator.desiredNudges(rules, listOf(line(qty = 10))).isEmpty())
        assertTrue(PromoCalculator.desiredNudges(rules, listOf(line(qty = 15))).isEmpty())
    }

    @Test
    fun hintNoteMatcher() {
        assertTrue(PromoCalculator.isHintNote("Add 1 more Redbull to get $1.00 off"))
        assertTrue(!PromoCalculator.isHintNote("extra shot"))
        assertTrue(!PromoCalculator.isHintNote(null))
    }

    @Test
    fun hintNoteMatcherHandlesVariants() {
        assertTrue(PromoCalculator.isHintNote("Add 12 more Try 5 to get $10.50 off"))
        assertTrue(PromoCalculator.isHintNote("  Add 1 more Redbull to get $1.00 off  "))
        assertTrue(!PromoCalculator.isHintNote(""))
        assertTrue(!PromoCalculator.isHintNote("   "))
        assertTrue(!PromoCalculator.isHintNote("Add one more Redbull to get $1.00 off"))
        assertTrue(!PromoCalculator.isHintNote("Add 1 more Redbull to get 1 dollar off"))
    }

    // ---- quantityFromUnitQty: Clover stores quantity in thousandths ----

    @Test
    fun quantityFromUnitQtyHandlesNullAndNonPositive() {
        assertEquals(1, PromoCalculator.quantityFromUnitQty(null))
        assertEquals(1, PromoCalculator.quantityFromUnitQty(0))
        assertEquals(1, PromoCalculator.quantityFromUnitQty(-5))
    }

    @Test
    fun quantityFromUnitQtyConvertsThousandths() {
        assertEquals(1, PromoCalculator.quantityFromUnitQty(1000))
        assertEquals(2, PromoCalculator.quantityFromUnitQty(2000))
        assertEquals(2, PromoCalculator.quantityFromUnitQty(2500))
        assertEquals(15, PromoCalculator.quantityFromUnitQty(15000))
    }

    @Test
    fun quantityFromUnitQtyTreatsSmallRawCountsAsUnits() {
        assertEquals(3, PromoCalculator.quantityFromUnitQty(3))
        assertEquals(999, PromoCalculator.quantityFromUnitQty(999))
    }

    // ---- matches: item id first, name only as a fallback ----

    @Test
    fun matchesByItemIdEvenWhenNameDiffers() {
        val line = CartLine("li", "item-try5", "Different Name", 100, 5)
        assertTrue(PromoCalculator.matches(line, fiveForFour))
    }

    @Test
    fun matchesAnyItemInAMultiItemPromo() {
        assertTrue(PromoCalculator.matches(flavor("l", redRb, "Red Bull Red"), redBullFlavors))
        assertTrue(PromoCalculator.matches(flavor("l", blueRb, "Red Bull Blue"), redBullFlavors))
        assertTrue(!PromoCalculator.matches(flavor("l", "item-other", "Monster"), redBullFlavors))
    }

    @Test
    fun matchesFallsBackToNameWhenNoItemId() {
        val nullId = CartLine("li", null, "try 5", 100, 5)
        val blankId = CartLine("li", "  ", "TRY 5", 100, 5)
        assertTrue(PromoCalculator.matches(nullId, fiveForFour))
        assertTrue(PromoCalculator.matches(blankId, fiveForFour))
    }

    @Test
    fun matchesFailsWithoutIdOrName() {
        val line = CartLine("li", null, null, 100, 5)
        assertTrue(!PromoCalculator.matches(line, fiveForFour))
    }

    // ---- discount edge cases ----

    @Test
    fun emptyInputsProduceNothing() {
        assertTrue(PromoCalculator.desiredDiscounts(emptyList(), emptyList()).isEmpty())
        assertTrue(PromoCalculator.desiredDiscounts(rules, emptyList()).isEmpty())
        assertTrue(PromoCalculator.desiredDiscounts(emptyList(), listOf(line(qty = 10))).isEmpty())
        assertTrue(PromoCalculator.desiredNudges(rules, emptyList()).isEmpty())
        assertTrue(PromoCalculator.desiredNotes(rules, emptyList()).isEmpty())
    }

    @Test
    fun ruleWithNoItemsIsIgnored() {
        val empty = fiveForFour.copy(items = emptyList())
        assertTrue(PromoCalculator.desiredDiscounts(listOf(empty), listOf(line(qty = 5))).isEmpty())
    }

    @Test
    fun zeroQuantityLineIsIgnored() {
        val discounts = PromoCalculator.desiredDiscounts(rules, listOf(line(qty = 0)))
        assertTrue(discounts.isEmpty())
    }

    @Test
    fun negativePriceLineIsIgnored() {
        val weird = CartLine("li", try5, "Try 5", unitPriceCents = -100, quantity = 10)
        assertTrue(PromoCalculator.desiredDiscounts(rules, listOf(weird)).isEmpty())
    }

    @Test
    fun ruleRequiringOneUnitIsIgnored() {
        val single = fiveForFour.copy(
            id = 9,
            name = "1 x Try 5 for $0.50",
            requiredQty = 1,
            bundlePriceCents = 50,
        )
        assertTrue(PromoCalculator.desiredDiscounts(listOf(single), listOf(line(qty = 5))).isEmpty())
    }

    @Test
    fun bundlePriceEqualToRetailGivesNoDiscount() {
        val breakEven = fiveForFour.copy(bundlePriceCents = 500) // 5 x $1.00 = $5.00
        assertTrue(PromoCalculator.desiredDiscounts(listOf(breakEven), listOf(line(qty = 5))).isEmpty())
    }

    @Test
    fun twentyUnitsOnOneLineMergeIntoASingleDiscountEntry() {
        val discounts = PromoCalculator.desiredDiscounts(listOf(tenForSeven), listOf(line(qty = 20)))
        assertEquals(1, discounts.size)
        assertEquals("PROMO: 10 x Try 5 for $7.00", discounts.single().name)
        assertEquals(-600L, discounts.single().amountCents)
    }

    @Test
    fun mixedPriceBundleStillSpreadsSavingsEvenly() {
        // $4.00 + $3.00 = $7.00 retail, 2-for-$5 saves $2.00 total.
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(twoRedbullForFive),
            listOf(rb("a", priceCents = 400), rb("b", priceCents = 300)),
        )
        assertEquals(2, discounts.size)
        assertEquals(-200L, discounts.sumOf { it.amountCents })
        assertTrue(discounts.all { it.amountCents == -100L })
    }

    @Test
    fun threeRedbullsDiscountTwoAndHintForTheLeftover() {
        val lines = listOf(rb("a"), rb("b"), rb("c"))
        val discounts = PromoCalculator.desiredDiscounts(listOf(twoRedbullForFive), lines)
        assertEquals(-100L, discounts.sumOf { it.amountCents })
        assertEquals(-50L, discounts.single { it.lineItemId == "a" }.amountCents)
        assertEquals(-50L, discounts.single { it.lineItemId == "b" }.amountCents)
        assertTrue(discounts.none { it.lineItemId == "c" })

        val notes = PromoCalculator.desiredNotes(listOf(twoRedbullForFive), lines)
        assertEquals("", notes["a"])
        assertEquals("", notes["b"])
        assertEquals("Add 1 more Redbull to get $1.00 off", notes["c"])
    }

    @Test
    fun notesSplitBetweenPackedAndLeftoverLines() {
        val notes = PromoCalculator.desiredNotes(
            rules,
            listOf(line(id = "a", qty = 5), line(id = "b", qty = 1)),
        )
        assertEquals("", notes["a"])
        assertEquals("Add 4 more Try 5 to get $2.00 off", notes["b"])
    }

    @Test
    fun belowThresholdHintsTowardTheOnlyLargePack() {
        val nudges = PromoCalculator.desiredNudges(listOf(tenForSeven), listOf(line(qty = 3)))
        assertEquals("Add 7 more Try 5 to get $3.00 off", nudges.single().message)
    }

    @Test
    fun noActiveRulesProduceNoNudgesOrNotes() {
        val inactive = listOf(fiveForFour.copy(active = false), tenForSeven.copy(active = false))
        assertTrue(PromoCalculator.desiredNudges(inactive, listOf(line(qty = 8))).isEmpty())
        assertTrue(PromoCalculator.desiredNotes(inactive, listOf(line(qty = 8))).isEmpty())
    }

    @Test
    fun oddDivisionSavingsStillRingExactBundlePrice() {
        val rule = PromoRule(
            id = 98, name = "3 x Candy for $4.00", label = "Candy",
            items = listOf(PromoItemRef("item-candy", "Candy")),
            requiredQty = 3, bundlePriceCents = 400,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(rule),
            listOf(CartLine("li1", "item-candy", "Candy", unitPriceCents = 149, quantity = 3)),
        )
        assertEquals(-47L, discounts.sumOf { it.amountCents })
    }

    @Test
    fun mixedPricePackCoversExpensiveUnitsAndStillDiscounts() {
        val rule = PromoRule(
            id = 97, name = "2 x Drink for $3.00", label = "Drink",
            items = listOf(PromoItemRef("cheap", "Cheap"), PromoItemRef("dear", "Dear")),
            requiredQty = 2, bundlePriceCents = 300,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(rule),
            listOf(
                CartLine("a", "cheap", "Cheap", unitPriceCents = 100, quantity = 1),
                CartLine("b", "cheap", "Cheap", unitPriceCents = 100, quantity = 1),
                CartLine("c", "dear", "Dear", unitPriceCents = 500, quantity = 1),
            ),
        )
        assertEquals(-300L, discounts.sumOf { it.amountCents })
        assertTrue(discounts.none { it.amountCents < -500L })
    }

    @Test
    fun capOverflowRedistributesToLinesWithRoom() {
        val rule = PromoRule(
            id = 96, name = "2 x Mix for $4.00", label = "Mix",
            items = listOf(PromoItemRef("big", "Big"), PromoItemRef("small", "Small")),
            requiredQty = 2, bundlePriceCents = 400,
        )
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(rule),
            listOf(
                CartLine("x", "big", "Big", unitPriceCents = 600, quantity = 1),
                CartLine("y", "small", "Small", unitPriceCents = 50, quantity = 1),
            ),
        )
        assertEquals(-250L, discounts.sumOf { it.amountCents })
    }

    // ---- schedule ----

    @Test
    fun weekendOnlyRuleDoesNotApplyOnWednesday() {
        val weekend = twoRedbullForFive.copy(daysOfWeek = PromoRule.WEEKEND_DAYS)
        val wednesday = at(2026, Calendar.AUGUST, 19, 12, 0)
        val discounts = PromoCalculator.desiredDiscounts(
            listOf(weekend),
            listOf(rb("a"), rb("b")),
            wednesday,
        )
        assertTrue(discounts.isEmpty())
        val saturday = at(2026, Calendar.AUGUST, 22, 12, 0)
        assertEquals(
            -100L,
            PromoCalculator.desiredDiscounts(listOf(weekend), listOf(rb("a"), rb("b")), saturday)
                .sumOf { it.amountCents },
        )
    }

    @Test
    fun timeWindowAppliesAtStartNotBeforeAndOvernightWraps() {
        val happyHour = twoRedbullForFive.copy(startMinute = 16 * 60, endMinute = 18 * 60)
        val cart = listOf(rb("a"), rb("b"))
        assertEquals(
            -100L,
            PromoCalculator.desiredDiscounts(listOf(happyHour), cart, at(2026, Calendar.AUGUST, 19, 16, 0))
                .sumOf { it.amountCents },
        )
        assertTrue(
            PromoCalculator.desiredDiscounts(listOf(happyHour), cart, at(2026, Calendar.AUGUST, 19, 15, 59))
                .isEmpty(),
        )

        val overnight = twoRedbullForFive.copy(startMinute = 22 * 60, endMinute = 2 * 60)
        assertEquals(
            -100L,
            PromoCalculator.desiredDiscounts(listOf(overnight), cart, at(2026, Calendar.AUGUST, 19, 23, 0))
                .sumOf { it.amountCents },
        )
        assertEquals(
            -100L,
            PromoCalculator.desiredDiscounts(listOf(overnight), cart, at(2026, Calendar.AUGUST, 19, 1, 0))
                .sumOf { it.amountCents },
        )
        assertTrue(
            PromoCalculator.desiredDiscounts(listOf(overnight), cart, at(2026, Calendar.AUGUST, 19, 12, 0))
                .isEmpty(),
        )
    }

    // ---- max uses per order ----

    @Test
    fun maxUsesCapsPacksAndLeavesRemainderWithoutHint() {
        val capped = twoRedbullForFive.copy(maxUsesPerOrder = 1)
        val lines = listOf(rb("li", qty = 4))
        val discounts = PromoCalculator.desiredDiscounts(listOf(capped), lines)
        assertEquals(-100L, discounts.sumOf { it.amountCents })
        assertTrue(PromoCalculator.desiredNudges(listOf(capped), lines).isEmpty())
    }

    // ---- bundle price tracking ----

    @Test
    fun trackSavingsKeepsOriginalDollarOffWhenRetailRises() {
        val track = PromoRule(
            id = 30,
            name = "3 x Candy, $0.50 off",
            label = "Candy",
            items = listOf(PromoItemRef("item-candy", "Candy", priceCents = 150)),
            requiredQty = 3,
            bundlePriceCents = 400,
            bundlePriceMode = BundlePriceMode.TRACK_SAVINGS,
            savingsCents = 50,
        )
        val cart = listOf(CartLine("a", "item-candy", "Candy", unitPriceCents = 200, quantity = 3))
        assertEquals(-50L, PromoCalculator.desiredDiscounts(listOf(track), cart).sumOf { it.amountCents })
    }

    @Test
    fun fixedPriceBundleGrowsSavingsWhenRetailRises() {
        val fixed = PromoRule(
            id = 31,
            name = "3 x Candy for $4.00",
            label = "Candy",
            items = listOf(PromoItemRef("item-candy", "Candy", priceCents = 150)),
            requiredQty = 3,
            bundlePriceCents = 400,
            bundlePriceMode = BundlePriceMode.FIXED_PRICE,
            savingsCents = 50,
        )
        val cart = listOf(CartLine("a", "item-candy", "Candy", unitPriceCents = 200, quantity = 3))
        assertEquals(-200L, PromoCalculator.desiredDiscounts(listOf(fixed), cart).sumOf { it.amountCents })
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun line(id: String = "li-try5", qty: Int) = CartLine(
        lineItemId = id,
        itemId = try5,
        itemName = "Try 5",
        unitPriceCents = 100,
        quantity = qty,
    )
}
