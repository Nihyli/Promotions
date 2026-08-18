package com.nihyli.cloverpromotions.engine

import com.nihyli.cloverpromotions.data.PromoRule
import java.util.Calendar

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
 * A promotion can cover several inventory items (e.g. drink flavors/SKUs); units
 * of any of them count together toward that promotion's packs. Rules that share
 * the exact same set of items form a "family" and their packs mix without
 * overlapping on the same unit (5-for-$4, 10-for-$7, leftover at regular price;
 * 15 → $7 + $4). Families are independent: a unit is claimed by the
 * earliest-created promotion whose items include it, so different promotions
 * never count the same unit.
 */
object PromoCalculator {
    const val PROMO_PREFIX = "PROMO: "
    private const val INF = Long.MAX_VALUE / 4
    private val hintNote = Regex("""^Add \d+ more .+ to get \$[\d.]+ off$""")

    fun desiredDiscounts(
        rules: List<PromoRule>,
        lines: List<CartLine>,
        now: Calendar = Calendar.getInstance(),
    ): List<DesiredDiscount> {
        val desired = mutableListOf<DesiredDiscount>()
        forEachFamily(rules, lines, now) { familyRules, _, units ->
            desired += allocatePacks(familyRules, units)
        }
        return mergeByLineAndName(desired)
    }

    /**
     * Closest next-pack hint while ringing, e.g. "Add 1 more Red Bull to get $1.00 off".
     * Stored as a line-item note (not a discount) so it is not a red PROMO line
     * and can be cleared before the receipt prints.
     */
    fun desiredNudges(
        rules: List<PromoRule>,
        lines: List<CartLine>,
        now: Calendar = Calendar.getInstance(),
    ): List<PromoNudge> {
        val nudges = mutableListOf<PromoNudge>()
        forEachFamily(rules, lines, now) { familyRules, _, units ->
            closestNudge(familyRules, units)?.let { nudges += it }
        }
        return nudges
    }

    /**
     * Note to put on each matching line so Register can stack identical rows.
     * Leftover units get the hint; packed units get "" (not null) so a cleared
     * hint on the first unit matches the others.
     */
    fun desiredNotes(
        rules: List<PromoRule>,
        lines: List<CartLine>,
        now: Calendar = Calendar.getInstance(),
    ): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        forEachFamily(rules, lines, now) { familyRules, matching, units ->
            val nudge = closestNudge(familyRules, units)
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

    /** True if [line] is one of the inventory items covered by [rule]. */
    fun matches(line: CartLine, rule: PromoRule): Boolean =
        rule.items.any { ref -> matchesItem(line, ref.id, ref.name) }

    private fun matchesItem(line: CartLine, itemId: String, itemName: String): Boolean {
        val lineItemId = line.itemId
        if (!lineItemId.isNullOrBlank()) return lineItemId == itemId
        val name = line.itemName ?: return false
        return name.equals(itemName, ignoreCase = true)
    }

    /** Clover stores quantity in thousandths (2 items → 2000). */
    fun quantityFromUnitQty(unitQty: Number?): Int {
        val qty = unitQty?.toLong() ?: return 1
        if (qty <= 0L) return 1
        if (qty < 1000L) return qty.toInt()
        return (qty / 1000L).toInt().coerceAtLeast(1)
    }

    /**
     * Groups active, in-effect rules into families keyed by their exact item set,
     * then walks families in creation order. Each line is claimed by the first
     * family whose items include it, so overlapping promotions never double-count.
     */
    private inline fun forEachFamily(
        rules: List<PromoRule>,
        lines: List<CartLine>,
        now: Calendar,
        block: (familyRules: List<PromoRule>, matching: List<CartLine>, units: List<UnitSlot>) -> Unit,
    ) {
        val families = rules
            .filter { it.active && it.requiredQty >= 2 && it.items.isNotEmpty() && it.isInEffect(now) }
            .groupBy { rule -> rule.items.map { it.id }.toSortedSet() }
            .values
            .sortedBy { group -> group.minOf { it.id } }

        val claimed = mutableSetOf<String>()
        for (familyRules in families) {
            val matching = lines
                .filter { line ->
                    line.lineItemId !in claimed &&
                        line.unitPriceCents >= 0 &&
                        line.quantity > 0 &&
                        familyRules.any { matches(line, it) }
                }
                .sortedBy { it.lineItemId }
            if (matching.isEmpty()) continue
            claimed += matching.map { it.lineItemId }
            val units = matching.flatMap { line ->
                List(line.quantity) { UnitSlot(line.lineItemId, line.unitPriceCents) }
            }.sortedByDescending { it.priceCents }
            block(familyRules, matching, units)
        }
    }

    /**
     * Cheapest way to ring [units] using the family's packs, then leftover at
     * regular price. Packs never cover the same unit twice.
     */
    private fun allocatePacks(familyRules: List<PromoRule>, units: List<UnitSlot>): List<DesiredDiscount> {
        val plan = planPacks(familyRules, units)
        if (plan.packs.isEmpty()) return emptyList()
        val discounts = mutableListOf<DesiredDiscount>()
        for (pack in plan.packs) {
            val slice = pack.unitIndices.map { units[it] }
            val savings = packSavings(pack.rule, slice)
            if (savings <= 0) continue
            discounts += evenDiscounts(pack.rule, slice, savings)
        }
        return discounts
    }

    private fun closestNudge(familyRules: List<PromoRule>, units: List<UnitSlot>): PromoNudge? {
        val current = planPacks(familyRules, units)
        val packedIdx = current.packs.flatMap { it.unitIndices }.toHashSet()
        val leftover = units.filterIndexed { index, _ -> index !in packedIdx }
        if (leftover.isEmpty()) return null

        val unitPrice = units.last().priceCents
        val label = familyRules.minByOrNull { it.id }?.groupDisplayName().orEmpty()
        val maxAdd = familyRules.maxOf { it.requiredQty }
        for (add in 1..maxAdd) {
            val extras = List(add) { UnitSlot(units.last().lineItemId, unitPrice) }
            val newCost = planPacks(familyRules, units + extras).totalCost
            val savings = current.totalCost + add * unitPrice - newCost
            if (savings <= 0) continue
            val more = if (add == 1) "1 more $label" else "$add more $label"
            return PromoNudge(
                lineItemIds = leftover.map { it.lineItemId }.toSet(),
                message = "Add $more to get ${formatCents(savings)} off",
            )
        }
        return null
    }

    private fun planPacks(familyRules: List<PromoRule>, units: List<UnitSlot>): PackPlan {
        val raw = planPacksDp(familyRules, units)
        val packs = limitMaxUses(raw.packs, units)
        return PackPlan(packs, customerCost(units, packs))
    }

    private fun planPacksDp(familyRules: List<PromoRule>, units: List<UnitSlot>): PackPlan {
        val n = units.size
        val cost = LongArray(n + 1) { INF }
        val prev = IntArray(n + 1) { -1 }
        val used = arrayOfNulls<PromoRule>(n + 1)
        cost[0] = 0L

        for (k in 1..n) {
            cost[k] = cost[k - 1] + units[k - 1].priceCents
            prev[k] = k - 1
            used[k] = null
            for (rule in familyRules) {
                val qty = rule.requiredQty
                if (k < qty) continue
                val candidate = cost[k - qty] + rule.bundlePriceCents
                val better = candidate < cost[k] ||
                    (candidate == cost[k] && used[k] != null && (used[k]?.requiredQty ?: 0) < qty)
                if (better) {
                    cost[k] = candidate
                    prev[k] = k - qty
                    used[k] = rule
                }
            }
        }

        val packs = mutableListOf<PlannedPack>()
        var k = n
        while (k > 0) {
            val rule = used[k]
            if (rule != null) {
                packs += PlannedPack(rule, (k - rule.requiredQty) until k)
                k = prev[k]
            } else {
                k -= 1
            }
        }
        return PackPlan(packs, cost[n])
    }

    /** Keep the packs that save the customer the most, up to each rule's cap. */
    private fun limitMaxUses(packs: List<PlannedPack>, units: List<UnitSlot>): List<PlannedPack> {
        if (packs.none { it.rule.maxUsesPerOrder > 0 }) return packs
        return packs.groupBy { it.rule.id }.values.flatMap { group ->
            val limit = group.first().rule.maxUsesPerOrder
            if (limit <= 0) group
            else group.sortedWith(
                compareByDescending<PlannedPack> { pack ->
                    packSavings(pack.rule, pack.unitIndices.map { units[it] })
                }.thenBy { it.unitIndices.first },
            ).take(limit)
        }
    }

    private fun customerCost(units: List<UnitSlot>, packs: List<PlannedPack>): Long {
        val savings = packs.sumOf { pack ->
            packSavings(pack.rule, pack.unitIndices.map { units[it] }).coerceAtLeast(0L)
        }
        return units.sumOf { it.priceCents } - savings
    }

    private fun packSavings(rule: PromoRule, slice: List<UnitSlot>): Long {
        if (slice.size < rule.requiredQty) return 0L
        return (slice.sumOf { it.priceCents } - rule.bundlePriceCents).coerceAtLeast(0L)
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
        if (totalSavings <= 0L) return emptyList()
        val perUnit = totalSavings / bundled.size

        val countByLine = bundled.groupingBy { it.lineItemId }.eachCount()
        val priceByLine = bundled.groupingBy { it.lineItemId }
            .fold(0L) { acc, unit -> acc + unit.priceCents }

        val amounts = linkedMapOf<String, Long>()
        for ((lineId, count) in countByLine) {
            amounts[lineId] = minOf(perUnit * count, priceByLine.getValue(lineId))
        }
        var leftover = totalSavings - amounts.values.sum()
        for (lineId in amounts.keys) {
            if (leftover <= 0L) break
            val room = priceByLine.getValue(lineId) - amounts.getValue(lineId)
            if (room <= 0L) continue
            val extra = minOf(room, leftover)
            amounts[lineId] = amounts.getValue(lineId) + extra
            leftover -= extra
        }

        return amounts.mapNotNull { (lineId, amount) ->
            if (amount <= 0L) return@mapNotNull null
            DesiredDiscount(
                lineItemId = lineId,
                name = PROMO_PREFIX + rule.displayTitle(),
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

    private data class PlannedPack(val rule: PromoRule, val unitIndices: IntRange)

    private data class PackPlan(val packs: List<PlannedPack>, val totalCost: Long)

    private data class UnitSlot(val lineItemId: String, val priceCents: Long)
}
