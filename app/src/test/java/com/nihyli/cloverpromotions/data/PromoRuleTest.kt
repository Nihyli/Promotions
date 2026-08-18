package com.nihyli.cloverpromotions.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

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
        assertTrue(looksLikeFullPromoTitle("2 x Candy, 20% off"))
        assertTrue(looksLikeFullPromoTitle("Buy 1 get 1 Candy"))
        assertTrue(!looksLikeFullPromoTitle("Red Bull"))
        assertTrue(!looksLikeFullPromoTitle("Candy"))
    }

    @Test
    fun trackSavingsTitleShowsAmountOff() {
        val rule = PromoRule(
            name = "",
            label = "Candy",
            items = listOf(PromoItemRef("c", "Candy", 150)),
            requiredQty = 3,
            bundlePriceCents = 400,
            bundlePriceMode = BundlePriceMode.TRACK_SAVINGS,
            savingsCents = 50,
        )
        assertEquals("3 x Candy, $0.50 off", rule.displayTitle())
    }

    @Test
    fun percentAndBogoTitles() {
        val percent = PromoRule(
            name = "",
            label = "Candy",
            items = listOf(PromoItemRef("c", "Candy")),
            requiredQty = 2,
            bundlePriceCents = 0,
            kind = PromoKind.PERCENT_OFF,
            percentOff = 20,
        )
        assertEquals("2 x Candy, 20% off", percent.displayTitle())

        val bogo = PromoRule(
            name = "",
            label = "Candy",
            items = listOf(PromoItemRef("c", "Candy")),
            requiredQty = 2,
            bundlePriceCents = 0,
            kind = PromoKind.BUY_X_GET_Y,
            buyQty = 1,
            getQty = 1,
        )
        assertEquals("Buy 1 get 1 Candy", bogo.displayTitle())
        val b2g1 = bogo.copy(buyQty = 2, getQty = 1, requiredQty = 3)
        assertEquals("Buy 2 get 1 Candy", b2g1.displayTitle())
    }

    @Test
    fun snapshotPackRetailSumsWhenQtyMatchesSelection() {
        val prices = listOf(150L, 200L)
        assertEquals(350L, snapshotPackRetail(prices, 2))
        assertEquals(525L, snapshotPackRetail(prices, 3)) // 3 * avg 175
    }

    @Test
    fun weekendScheduleSkipsWednesday() {
        val rule = PromoRule(
            name = "2 x Drink for $5.00",
            label = "Drink",
            items = listOf(PromoItemRef("d", "Drink")),
            requiredQty = 2,
            bundlePriceCents = 500,
            daysOfWeek = PromoRule.WEEKEND_DAYS,
        )
        assertTrue(!rule.isInEffect(at(2026, Calendar.AUGUST, 19, 12, 0)))
        assertTrue(rule.isInEffect(at(2026, Calendar.AUGUST, 22, 12, 0)))
        assertTrue(rule.isInEffect(at(2026, Calendar.AUGUST, 23, 12, 0)))
    }

    @Test
    fun timeWindowIsHalfOpenAndSupportsOvernight() {
        val happyHour = PromoRule(
            name = "2 x Drink for $5.00",
            label = "Drink",
            items = listOf(PromoItemRef("d", "Drink")),
            requiredQty = 2,
            bundlePriceCents = 500,
            startMinute = 16 * 60,
            endMinute = 18 * 60,
        )
        assertTrue(happyHour.isInEffect(at(2026, Calendar.AUGUST, 19, 16, 0)))
        assertTrue(!happyHour.isInEffect(at(2026, Calendar.AUGUST, 19, 15, 59)))
        assertTrue(happyHour.isInEffect(at(2026, Calendar.AUGUST, 19, 17, 59)))
        assertTrue(!happyHour.isInEffect(at(2026, Calendar.AUGUST, 19, 18, 0)))

        val overnight = happyHour.copy(startMinute = 22 * 60, endMinute = 2 * 60)
        assertTrue(overnight.isInEffect(at(2026, Calendar.AUGUST, 19, 23, 0)))
        assertTrue(overnight.isInEffect(at(2026, Calendar.AUGUST, 19, 1, 0)))
        assertTrue(!overnight.isInEffect(at(2026, Calendar.AUGUST, 19, 12, 0)))
    }

    @Test
    fun parseClockMinutesAcceptsHourAndHhMm() {
        assertEquals(16 * 60, parseClockMinutes("16:00"))
        assertEquals(16 * 60, parseClockMinutes("16"))
        assertEquals(4 * 60 + 5, parseClockMinutes("4:05"))
        assertEquals(PromoRule.END_OF_DAY_MINUTE, parseClockMinutes("24:00"))
        assertEquals(null, parseClockMinutes("25:00"))
        assertEquals(null, parseClockMinutes("16:60"))
        assertEquals(null, parseClockMinutes(""))
    }

    @Test
    fun scheduleSummaryIsCompact() {
        val weekends = PromoRule(
            name = "",
            label = "Candy",
            items = listOf(PromoItemRef("c", "Candy")),
            requiredQty = 2,
            bundlePriceCents = 500,
            daysOfWeek = PromoRule.WEEKEND_DAYS,
            startMinute = 16 * 60,
            endMinute = 18 * 60,
        )
        assertEquals("Weekends 4pm–6pm", weekends.scheduleSummary())
        val always = weekends.copy(daysOfWeek = PromoRule.ALL_DAYS, startMinute = 0, endMinute = 1440)
        assertEquals(null, always.scheduleSummary())
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
