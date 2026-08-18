package com.nihyli.cloverpromotions.ui

/**
 * Matches picker search against an item's name, SKU, or barcode (UPC/EAN).
 * Scanned codes often drop or keep a leading zero compared to inventory, so
 * codes are also compared with leading zeros stripped.
 */
object InventoryQuery {
    fun matches(name: String, sku: String, barcode: String, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        if (name.contains(needle, ignoreCase = true)) return true
        if (sku.contains(needle, ignoreCase = true)) return true
        if (barcode.contains(needle, ignoreCase = true)) return true
        val normalized = normalizeCode(needle)
        if (normalized.isEmpty()) return false
        return normalizeCode(sku) == normalized || normalizeCode(barcode) == normalized
    }

    private fun normalizeCode(value: String): String =
        value.trim().trimStart('0').lowercase()
}
