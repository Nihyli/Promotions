package com.nihyli.cloverpromotions.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoRuleTest {

    @Test
    fun pollutedLabelDoesNotDuplicateInTitle() {
        val rule = PromoRule(
            name = "3 x 3 for 3 Candy for $3.00",
            label = "3 for 3 Candy",
            items = listOf(PromoItemRef("c", "Candy")),
            requiredQty = 3,
            bundlePriceCents = 300,
        )
        assertEquals("Candy", rule.groupDisplayName())
        assertEquals("3 x Candy for $3.00", rule.displayTitle())
    }

    @Test
    fun storedFullTitleAsLabelFallsBackToItemName() {
        val rule = PromoRule(
            name = "2 x Red Bull for $5.00",
            label = "2 x Red Bull for $5.00",
            items = listOf(PromoItemRef("rb", "Red Bull")),
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        assertEquals("Red Bull", rule.groupDisplayName())
        assertEquals("2 x Red Bull for $5.00", rule.displayTitle())
    }

    @Test
    fun cleanGroupLabelIsKeptForMultiItemPromos() {
        val rule = PromoRule(
            name = "2 x Red Bull for $5.00",
            label = "Red Bull",
            items = listOf(
                PromoItemRef("red", "Red Bull Red"),
                PromoItemRef("blue", "Red Bull Blue"),
            ),
            requiredQty = 2,
            bundlePriceCents = 500,
        )
        assertEquals("Red Bull", rule.groupDisplayName())
        assertEquals("2 x Red Bull for $5.00", rule.displayTitle())
    }

    @Test
    fun looksLikeFullPromoTitleDetectsWrappedNames() {
        assertTrue(looksLikeFullPromoTitle("3 x Candy for $3.00"))
        assertTrue(looksLikeFullPromoTitle("3 for 3 Candy"))
        assertTrue(looksLikeFullPromoTitle("2 x Red Bull"))
        assertTrue(!looksLikeFullPromoTitle("Red Bull"))
        assertTrue(!looksLikeFullPromoTitle("Candy"))
    }
}
