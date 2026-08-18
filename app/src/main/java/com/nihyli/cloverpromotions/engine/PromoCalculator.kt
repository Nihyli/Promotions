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

data class PromoNudge(
    val lineItemIds: Set<String>,
    val message: String,
)

/**
 * Picks promotion discounts for a cart.
 *
 * Multiple rules for the same item do not overlap on the same unit. For each
 * item we pick the cheapest mix of packs (5-for-$4, 10-for-$7, leftover at
 * regular price). Example: 5 → $4 deal, 10 → $7 deal, 15 → $7 + $4.
 */
object PromoCalculator {
    const val PROMO_PREFIX = "PROMO: "
    private const val INF = Long.MAX_VALUE / 4
    private val hintNote = Regex("""^Add \d+ more .+ to get \$[\d.]+ off$""")

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
            desired += allocatePacks(itemRules, units)
        }
        return mergeByLineAndName(desired)
    }

    /**
     * Closest next-pack hint while ringing, e.g. "Add 1 more Redbull to get $1.00 off".
     * Stored as a line-item note (not a discount) so it is not a red PROMO line
     * and can be cleared before the receipt prints.
     */
    fun desiredNudges(rules: List<PromoRule>, lines: List<CartLine>): List<PromoNudge> {
        val usableRules = rules.filter { it.active && it.requiredQty >= 2 }
        val nudges = mutableListOf<PromoNudge>()
        for ((_, itemRules) in usableRules.groupBy { it.itemId }) {
            val matching = lines
                .filter { line -> itemRules.any { matches(line, it) } && line.unitPriceCents >= 0 && line.quantity > 0 }
                .sortedBy { it.lineItemId }
            val units = matching.flatMap { line ->
                List(line.quantity) { UnitSlot(line.lineItemId, line.unitPriceCents) }
            }
            if (units.isEmpty()) continue
            closestNudge(itemRules, units)?.let { nudges += it }
        }
        return nudges
    }

    /**
     * Note to put on each matching line so Register can stack identical rows.
     * Leftover units get the hint; packed units get "" (not null) so a cleared
     * hint on the first Red Bull matches the second Red Bull.
     */
    fun desiredNotes(rules: List<PromoRule>, lines: List<CartLine>): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        val usableRules = rules.filter { it.active && it.requiredQty >= 2 }
        for ((_, itemRules) in usableRules.groupBy { it.itemId }) {
            val matching = lines
                .filter { line -> itemRules.any { matches(line, it) } && line.unitPriceCents >= 0 && line.quantity > 0 }
                .sortedBy { it.lineItemId }
            val units = matching.flatMap { line ->
                List(line.quantity) { UnitSlot(line.lineItemId, line.unitPriceCents) }
            }
            if (units.isEmpty()) continue
            val nudge = closestNudge(itemRules, units)
            for (line in matching) {
                notes[line.lineItemId] =
                    if (nudge != null && line.lineItemId in nudge.lineItemIds) nudge.message else ""
            }
        }
        return notes
    }

    fun isHintNote(note: String?): Boolean {
        val text = note?.trim().orEmpty()
        return text.isNotEmpty() && hintNote.matches(text)
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

    /**
     * Cheapest way to ring [units] using the item's packs, then leftover at
     * regular price. Packs never cover the same unit twice.
     */
    private fun allocatePacks(itemRules: List<PromoRule>, units: List<UnitSlot>): List<DesiredDiscount> {
        val plan = planPacks(itemRules, units)
        if (plan.packs.isEmpty()) return emptyList()
        val discounts = mutableListOf<DesiredDiscount>()
        var idx = 0
        for (rule in plan.packs) {
            val slice = units.subList(idx, idx + rule.requiredQty).toList()
            idx += rule.requiredQty
            val savings = slice.sumOf { it.priceCents } - rule.bundlePriceCents
            if (savings <= 0) continue
            discounts += evenDiscounts(rule, slice, savings)
        }
        return discounts
    }

    private fun closestNudge(itemRules: List<PromoRule>, units: List<UnitSlot>): PromoNudge? {
        val current = planPacks(itemRules, units)
        val packedQty = current.packs.sumOf { it.requiredQty }
        val leftover = units.drop(packedQty)
        if (leftover.isEmpty()) return null

        val unitPrice = units.last().priceCents
        val maxAdd = itemRules.maxOf { it.requiredQty }
        for (add in 1..maxAdd) {
            val extras = List(add) { UnitSlot(units.last().lineItemId, unitPrice) }
            val newCost = planPacks(itemRules, units + extras).totalCost
            val savings = current.totalCost + add * unitPrice - newCost
            if (savings <= 0) continue
            val name = itemRules.first().itemName
            val more = if (add == 1) "1 more $name" else "$add more $name"
            return PromoNudge(
                lineItemIds = leftover.map { it.lineItemId }.toSet(),
                message = "Add $more to get ${formatCents(savings)} off",
            )
        }
        return null
    }

    private fun planPacks(itemRules: List<PromoRule>, units: List<UnitSlot>): PackPlan {
        val n = units.size
        val cost = LongArray(n + 1) { INF }
        val prev = IntArray(n + 1) { -1 }
        val used = arrayOfNulls<PromoRule>(n + 1)
        cost[0] = 0L

        for (k in 1..n) {
            cost[k] = cost[k - 1] + units[k - 1].priceCents
            prev[k] = k - 1
            used[k] = null
            for (rule in itemRules) {
                val qty = rule.requiredQty
                if (k < qty) continue
                val candidate = cost[k - qty] + rule.bundlePriceCents
                val better = candidate < cost[k] ||
                    (candidate == cost[k] && used[k] == null) ||
                    (candidate == cost[k] && (used[k]?.requiredQty ?: 0) < qty)
                if (better) {
                    cost[k] = candidate
                    prev[k] = k - qty
                    used[k] = rule
                }
            }
        }

        val packs = mutableListOf<PromoRule>()
        var k = n
        while (k > 0) {
            val rule = used[k]
            if (rule != null) {
                packs += rule
                k = prev[k]
            } else {
                k -= 1
            }
        }
        packs.sortByDescending { it.requiredQty }
        return PackPlan(packs, cost[n])
    }

    private fun formatCents(cents: Long): String =
        java.lang.String.format(java.util.Locale.US, "$%.2f", cents / 100.0)

    /**
     * Register stacks identical lines (same item + same discount) into one
     * "x10" row. Give every unit in a pack the same per-unit amount so that
     * pack stays on one row, and never exceed a line's price.
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

    private fun mergeByLineAndName(discounts: List<DesiredDiscount>): List<DesiredDiscount> =
        discounts
            .groupBy { it.lineItemId to it.name }
            .map { (key, group) ->
                DesiredDiscount(
                    lineItemId = key.first,
                    name = key.second,
                    amountCents = group.sumOf { it.amountCents },
                )
            }

    private data class PackPlan(val packs: List<PromoRule>, val totalCost: Long)

    private data class UnitSlot(val lineItemId: String, val priceCents: Long)
}
