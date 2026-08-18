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
            val order = connector.getOrder(orderId)
            if (order == null) {
                Log.w(TAG, "Order $orderId not found")
                return@withLock
            }

            val lineItems = order.lineItems.orEmpty()
                .filter { it.id != null }
            val rules = PromoDatabase.get(context).rules().activeRules()

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
     * For each rule, group qualifying line items into bundles of
     * [PromoRule.requiredQty] and put the whole bundle's savings on the last
     * line item of the bundle, so 2 x $3.00 with a $5.00 bundle price shows a
     * single -$1.00 discount.
     */
    private fun computeDesiredDiscounts(
        rules: List<PromoRule>,
        lineItems: List<com.clover.sdk.v3.order.LineItem>,
    ): List<DesiredDiscount> {
        val desired = mutableListOf<DesiredDiscount>()
        for (rule in rules) {
            if (rule.requiredQty < 2) continue
            val matching = lineItems
                .filter { it.item?.id == rule.itemId && it.price != null }
                .sortedBy { it.id } // stable bundle assignment across recomputes

            val bundleCount = matching.size / rule.requiredQty
            for (bundle in 0 until bundleCount) {
                val bundleItems = matching.subList(
                    bundle * rule.requiredQty,
                    (bundle + 1) * rule.requiredQty,
                )
                val bundleTotal = bundleItems.sumOf { it.price ?: 0L }
                val savings = bundleTotal - rule.bundlePriceCents
                if (savings <= 0) continue
                desired += DesiredDiscount(
                    lineItemId = bundleItems.last().id,
                    name = PROMO_PREFIX + rule.name,
                    amountCents = -savings,
                )
            }
        }
        return desired
    }
}
