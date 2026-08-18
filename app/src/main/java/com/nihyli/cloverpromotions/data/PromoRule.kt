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
)
