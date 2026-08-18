package com.nihyli.cloverpromotions.engine

import com.nihyli.cloverpromotions.data.PromoRule

/** One scanned unit of an item on the order (Register may combine many onto one line). */
data class CartLine(
    val lineItemId: String,
    val itemId: String?,
    val itemName: String?,
    val unitPriceCents: Long,
    val quantity: Int,
)

data class DesiredDiscount(
    val lineItemId: String,
    val name: String,
    val amountCents: Long,
)

/**
 * Picks promotion discounts for a cart.
 *
 * Multiple rules for the same item do **not** stack. For each item we evaluate
 * every matching rule on the full quantity and keep only the single best deal
 * (highest savings). Leftover units ring at regular price.
 */
object PromoCalculator {
    const val PROMO_PREFIX = "PROMO: "

    fun desiredDiscounts(rules: List<PromoRule>, lines: List<CartLine>): List<DesiredDiscount> {
        val usableRules = rules.filter { it.active && it.requiredQty >= 2 }
        val desired = mutableListOf<DesiredDiscount>()

        for ((_, itemRules) in usableRules.groupBy { it.itemId }) {
            val matching = lines
                .filter { line -> itemRules.any { matches(line, it) } && line.unitPriceCents >= 0 && line.quantity > 0 }
                .sortedBy { it.lineItemId }
            val units = matching.flatMap { line ->
                List(line.quantity) { UnitSlot(line.lineItemId, line.unitPriceCents) }
            }
            if (units.isEmpty()) continue

            val winner = itemRules
                .mapNotNull { evaluate(it, units) }
                .maxWithOrNull(
                    compareBy<RuleEval>(
                        { it.totalSavings },
                        { -it.leftover },
                        { it.requiredQty },
                    ),
                )
                ?: continue

            desired += winner.discounts
        }
        return desired
    }

    fun matches(line: CartLine, rule: PromoRule): Boolean {
        val itemId = line.itemId
        if (!itemId.isNullOrBlank()) return itemId == rule.itemId
        val name = line.itemName ?: return false
        return name.equals(rule.itemName, ignoreCase = true)
    }

    /** Clover stores quantity in thousandths (2 items → 2000). */
    fun quantityFromUnitQty(unitQty: Number?): Int {
        val qty = unitQty?.toLong() ?: return 1
        if (qty <= 0L) return 1
        if (qty < 1000L) return qty.toInt()
        return (qty / 1000L).toInt().coerceAtLeast(1)
    }

    private fun evaluate(rule: PromoRule, units: List<UnitSlot>): RuleEval? {
        val bundleCount = units.size / rule.requiredQty
        if (bundleCount == 0) return null

        val savingsByLine = linkedMapOf<String, Long>()
        var totalSavings = 0L
        val remainingCapacity = units
            .groupingBy { it.lineItemId }
            .fold(0L) { acc, unit -> acc + unit.priceCents }
            .toMutableMap()

        for (bundle in 0 until bundleCount) {
            val bundleUnits = units.subList(
                bundle * rule.requiredQty,
                (bundle + 1) * rule.requiredQty,
            )
            val savings = bundleUnits.sumOf { it.priceCents } - rule.bundlePriceCents
            if (savings <= 0) continue
            totalSavings += savings
            for ((lineId, take) in spreadSavings(bundleUnits, savings, remainingCapacity)) {
                savingsByLine[lineId] = (savingsByLine[lineId] ?: 0L) + take
            }
        }
        if (totalSavings <= 0 || savingsByLine.isEmpty()) return null

        return RuleEval(
            requiredQty = rule.requiredQty,
            leftover = units.size - bundleCount * rule.requiredQty,
            totalSavings = totalSavings,
            discounts = savingsByLine.map { (lineId, savings) ->
                DesiredDiscount(
                    lineItemId = lineId,
                    name = PROMO_PREFIX + rule.name,
                    amountCents = -savings,
                )
            },
        )
    }

    /**
     * Clover will not take more off a line than that line's price, so a $3
     * bundle discount parked on a $1 line only reduces the total by $1.
     * Spread savings across the lines that actually make up the bundle.
     */
    private fun spreadSavings(
        bundleUnits: List<UnitSlot>,
        savings: Long,
        remainingCapacity: MutableMap<String, Long>,
    ): Map<String, Long> {
        val bundleCapacity = linkedMapOf<String, Long>()
        for (unit in bundleUnits) {
            bundleCapacity[unit.lineItemId] = (bundleCapacity[unit.lineItemId] ?: 0L) + unit.priceCents
        }
        val allocated = linkedMapOf<String, Long>()
        var remaining = savings
        val lineOrder = bundleCapacity.entries.sortedWith(
            compareByDescending<Map.Entry<String, Long>> { minOf(it.value, remainingCapacity[it.key] ?: 0L) }
                .thenBy { it.key },
        )
        for ((lineId, _) in lineOrder) {
            if (remaining <= 0) break
            val room = minOf(bundleCapacity[lineId] ?: 0L, remainingCapacity[lineId] ?: 0L)
            val take = minOf(remaining, room)
            if (take <= 0) continue
            allocated[lineId] = take
            remainingCapacity[lineId] = (remainingCapacity[lineId] ?: 0L) - take
            remaining -= take
        }
        return allocated
    }

    private data class UnitSlot(val lineItemId: String, val priceCents: Long)

    private data class RuleEval(
        val requiredQty: Int,
        val leftover: Int,
        val totalSavings: Long,
        val discounts: List<DesiredDiscount>,
    )
}
