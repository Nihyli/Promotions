package com.nihyli.cloverpromotions.engine

import android.content.Context
import android.util.Log
import com.clover.sdk.util.CloverAccount
import com.clover.sdk.v3.order.Discount
import com.clover.sdk.v3.order.OrderConnector
import com.nihyli.cloverpromotions.data.PromoDatabase
import com.nihyli.cloverpromotions.data.PromoRule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recomputes and applies promotion discounts for an order.
 *
 * Idempotent: every run derives the full set of discounts this app *should*
 * have on the order (tagged with [PROMO_PREFIX]) and reconciles the order to
 * match — adding missing ones and deleting stale ones. Safe to call on every
 * LINE_ITEM_ADDED / LINE_ITEM_DELETED broadcast.
 */
object PromoEngine {
    private const val TAG = "PromoEngine"
    const val PROMO_PREFIX = "PROMO: "

    /** Serializes recomputes so rapid scans don't interleave. */
    private val mutex = Mutex()

    private data class DesiredDiscount(val lineItemId: String, val name: String, val amountCents: Long)

    suspend fun recompute(context: Context, orderId: String): Unit = mutex.withLock {
        val account = CloverAccount.getAccount(context)
        if (account == null) {
            Log.w(TAG, "No Clover account on this device; skipping")
            return@withLock
        }

        val connector = OrderConnector(context, account, null)
        try {
            connector.connect()
            if (!waitUntilConnected(connector)) {
                Log.e(TAG, "OrderConnector did not connect; skipping $orderId")
                return@withLock
            }
            val order = connector.getOrder(orderId)
            if (order == null) {
                Log.w(TAG, "Order $orderId not found")
                return@withLock
            }

            val lineItems = order.lineItems.orEmpty()
                .filter { it.id != null }
            val rules = PromoDatabase.get(context).rules().activeRules()
            Log.i(
                TAG,
                "Recompute $orderId: ${lineItems.size} line(s), ${rules.size} active rule(s). " +
                    lineItems.joinToString { li ->
                        "${li.name} id=${li.item?.id} qty=${unitCount(li)} price=${li.price}"
                    },
            )

            val desired = computeDesiredDiscounts(rules, lineItems)
            val desiredByLine = desired.groupBy { it.lineItemId }

            for (lineItem in lineItems) {
                val existing = lineItem.discounts.orEmpty()
                    .filter { it.name?.startsWith(PROMO_PREFIX) == true }
                val wanted = desiredByLine[lineItem.id].orEmpty()

                val existingSig = existing.map { "${it.name}|${it.amount}" }.sorted()
                val wantedSig = wanted.map { "${it.name}|${it.amountCents}" }.sorted()
                if (existingSig == wantedSig) continue

                val staleIds = existing.mapNotNull { it.id }
                if (staleIds.isNotEmpty()) {
                    connector.deleteLineItemDiscounts(orderId, lineItem.id, staleIds)
                    Log.i(TAG, "Removed ${staleIds.size} stale promo discount(s) from line item ${lineItem.id}")
                }
                for (want in wanted) {
                    val discount = Discount().apply {
                        name = want.name
                        amount = want.amountCents
                    }
                    connector.addLineItemDiscount(orderId, lineItem.id, discount)
                    Log.i(TAG, "Applied '${want.name}' (${want.amountCents}c) to line item ${lineItem.id}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recompute promos for order $orderId", e)
        } finally {
            connector.disconnect()
        }
    }

    /**
     * For each rule, expand line items by Register quantity (Clover often
     * combines 2 scans into one line with unitQty=2000) and group units into
     * bundles of [PromoRule.requiredQty]. Savings for a bundle land on the last
     * line item that contributed a unit, so 2 x $3.00 for $5.00 shows -$1.00.
     */
    private fun computeDesiredDiscounts(
        rules: List<PromoRule>,
        lineItems: List<com.clover.sdk.v3.order.LineItem>,
    ): List<DesiredDiscount> {
        val desired = mutableListOf<DesiredDiscount>()
        for (rule in rules) {
            if (rule.requiredQty < 2) continue
            val matching = lineItems
                .filter { matches(it, rule) && it.price != null }
                .sortedBy { it.id }

            val units = matching.flatMap { li ->
                List(unitCount(li)) { UnitSlot(li.id, li.price ?: 0L) }
            }
            val bundleCount = units.size / rule.requiredQty
            Log.i(
                TAG,
                "Rule '${rule.name}' matched ${units.size} unit(s) across ${matching.size} line(s) → $bundleCount bundle(s)",
            )
            val savingsByLine = linkedMapOf<String, Long>()
            for (bundle in 0 until bundleCount) {
                val bundleUnits = units.subList(
                    bundle * rule.requiredQty,
                    (bundle + 1) * rule.requiredQty,
                )
                val savings = bundleUnits.sumOf { it.priceCents } - rule.bundlePriceCents
                if (savings <= 0) continue
                val lineId = bundleUnits.last().lineItemId
                savingsByLine[lineId] = (savingsByLine[lineId] ?: 0L) + savings
            }
            for ((lineId, savings) in savingsByLine) {
                desired += DesiredDiscount(
                    lineItemId = lineId,
                    name = PROMO_PREFIX + rule.name,
                    amountCents = -savings,
                )
            }
        }
        return desired
    }

    private fun matches(lineItem: com.clover.sdk.v3.order.LineItem, rule: PromoRule): Boolean {
        val itemId = lineItem.item?.id
        if (!itemId.isNullOrBlank() && itemId == rule.itemId) return true
        val name = lineItem.name ?: return false
        return name.equals(rule.itemName, ignoreCase = true)
    }

    /** Clover stores quantity in thousandths (2 items → 2000). */
    private fun unitCount(lineItem: com.clover.sdk.v3.order.LineItem): Int {
        val qty = lineItem.unitQty ?: return 1
        if (qty <= 0L) return 1
        if (qty < 1000L) return qty.toInt()
        return (qty / 1000L).toInt().coerceAtLeast(1)
    }

    private fun waitUntilConnected(connector: OrderConnector, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!connector.isConnected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return connector.isConnected
            }
        }
        return connector.isConnected
    }

    private data class UnitSlot(val lineItemId: String, val priceCents: Long)
}
