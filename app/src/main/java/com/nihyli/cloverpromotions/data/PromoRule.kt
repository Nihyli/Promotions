package com.nihyli.cloverpromotions.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One inventory item that belongs to a promotion (e.g. a single Red Bull flavor/SKU). */
data class PromoItemRef(val id: String, val name: String)

/**
 * A quantity bundle promotion. Buy [requiredQty] units drawn from any of the
 * promotion's [items] and the bundle rings up at [bundlePriceCents] total.
 *
 * Multiple items (e.g. different flavors/SKUs of the same drink) can share one
 * promotion, and units of any of them count together toward the same deal:
 * 1 red Red Bull + 1 blue Red Bull satisfies a "2 for $5" promo. Units from a
 * different promotion never count toward this one.
 *
 * [label] is the shared display name for the group (e.g. "Red Bull") used in the
 * promotion name and cashier hints.
 */
@Entity(tableName = "promo_rules")
data class PromoRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val label: String,
    val items: List<PromoItemRef>,
    val requiredQty: Int,
    val bundlePriceCents: Long,
    val active: Boolean = true,
) {
    /**
     * Short group name for titles and hints (e.g. "Candy", "Red Bull").
     * Ignores a stored label that already looks like a full “3 x … for $…” title.
     */
    fun groupDisplayName(): String {
        val fromItems = items.firstOrNull()?.name.orEmpty()
        val trimmed = label.trim()
        if (trimmed.isEmpty() || looksLikeFullPromoTitle(trimmed)) {
            return fromItems.ifBlank { trimmed }
        }
        return trimmed
    }

    /** Title shown in the list and on receipts, e.g. "3 x Candy for $3.00". */
    fun displayTitle(): String {
        val dollars = java.lang.String.format(java.util.Locale.US, "$%.2f", bundlePriceCents / 100.0)
        return "$requiredQty x ${groupDisplayName()} for $dollars"
    }
}

/** True if [text] already includes qty/price wording, so wrapping it again would duplicate. */
fun looksLikeFullPromoTitle(text: String): Boolean {
    val value = text.trim()
    if (value.contains(" for $", ignoreCase = true)) return true
    if (value.contains(" for ", ignoreCase = true) && value.firstOrNull()?.isDigit() == true) return true
    if (Regex("""^\d+\s*x\s""", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
    return false
}
