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

        var totalSavings = 0L
        val bundled = mutableListOf<UnitSlot>()
        for (bundle in 0 until bundleCount) {
            val bundleUnits = units.subList(
                bundle * rule.requiredQty,
                (bundle + 1) * rule.requiredQty,
            )
            val savings = bundleUnits.sumOf { it.priceCents } - rule.bundlePriceCents
            if (savings <= 0) continue
            totalSavings += savings
            bundled += bundleUnits
        }
        if (totalSavings <= 0 || bundled.isEmpty()) return null

        val discounts = evenDiscounts(rule, bundled, totalSavings)
        if (discounts.isEmpty()) return null

        return RuleEval(
            requiredQty = rule.requiredQty,
            leftover = units.size - bundleCount * rule.requiredQty,
            totalSavings = totalSavings,
            discounts = discounts,
        )
    }

    /**
     * Register stacks identical lines (same item + same discount) into one
     * "x10" row. A discount on only some of those lines splits the stack
     * (x7 + x3). Give every bundled unit the same per-unit amount so they
     * stay on one row with the promo underneath, and never exceed a line's
     * price so Clover actually takes the money off.
     */
    private fun evenDiscounts(
        rule: PromoRule,
        bundled: List<UnitSlot>,
        totalSavings: Long,
    ): List<DesiredDiscount> {
        val perUnit = totalSavings / bundled.size
        if (perUnit <= 0L) return emptyList()

        val countByLine = bundled.groupingBy { it.lineItemId }.eachCount()
        val priceByLine = bundled.groupingBy { it.lineItemId }
            .fold(0L) { acc, unit -> acc + unit.priceCents }

        return countByLine.map { (lineId, count) ->
            val amount = minOf(perUnit * count, priceByLine.getValue(lineId))
            DesiredDiscount(
                lineItemId = lineId,
                name = PROMO_PREFIX + rule.name,
                amountCents = -amount,
            )
        }
    }

    private data class UnitSlot(val lineItemId: String, val priceCents: Long)

    private data class RuleEval(
        val requiredQty: Int,
        val leftover: Int,
        val totalSavings: Long,
        val discounts: List<DesiredDiscount>,
    )
}
