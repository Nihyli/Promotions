package com.nihyli.cloverpromotions.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A quantity bundle promotion: buy [requiredQty] of the inventory item
 * [itemId] and the bundle rings up at [bundlePriceCents] total.
 * Example: 2 x Red Bull ($3.00 each) for $5.00 -> requiredQty=2, bundlePriceCents=500.
 */
@Entity(tableName = "promo_rules")
data class PromoRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val itemId: String,
    val itemName: String,
    val requiredQty: Int,
    val bundlePriceCents: Long,
    val active: Boolean = true,
)
