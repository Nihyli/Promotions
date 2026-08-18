package com.nihyli.cloverpromotions.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryQueryTest {

    @Test
    fun matchesName() {
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345", "can"))
        assertTrue(!InventoryQuery.matches("Candy", "CND-1", "012345", "soda"))
    }

    @Test
    fun matchesSku() {
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345", "CND"))
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345", "cnd-1"))
    }

    @Test
    fun matchesBarcode() {
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345678905", "012345678905"))
    }

    @Test
    fun scannedBarcodeIgnoresLeadingZeroMismatch() {
        // Scanner often drops or keeps a UPC leading zero vs inventory CODE.
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345678905", "12345678905"))
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "12345678905", "012345678905"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345", ""))
        assertTrue(InventoryQuery.matches("Candy", "CND-1", "012345", "   "))
    }
}
