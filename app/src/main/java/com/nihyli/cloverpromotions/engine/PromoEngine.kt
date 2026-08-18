package com.nihyli.cloverpromotions.engine

import android.content.Context
import android.util.Log
import com.clover.sdk.util.CloverAccount
import com.clover.sdk.v3.order.Discount
import com.clover.sdk.v3.order.OrderConnector
import com.nihyli.cloverpromotions.data.PromoDatabase
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
    const val PROMO_PREFIX = PromoCalculator.PROMO_PREFIX

    /** Serializes recomputes so rapid scans don't interleave. */
    private val mutex = Mutex()

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
                        "${li.name} id=${li.item?.id} qty=${PromoCalculator.quantityFromUnitQty(li.unitQty)} price=${li.price}"
                    },
            )

            val lines = lineItems.mapNotNull { li ->
                val id = li.id ?: return@mapNotNull null
                val price = li.price ?: return@mapNotNull null
                CartLine(
                    lineItemId = id,
                    itemId = li.item?.id,
                    itemName = li.name,
                    unitPriceCents = price,
                    quantity = PromoCalculator.quantityFromUnitQty(li.unitQty),
                )
            }
            val desired = PromoCalculator.desiredDiscounts(rules, lines)
            Log.i(
                TAG,
                "Desired discounts: " +
                    desired.joinToString { "${it.name} ${it.amountCents}c on ${it.lineItemId}" },
            )
            val desiredByLine = desired.groupBy { it.lineItemId }
            val ringing = order.payments.isNullOrEmpty()
            val noteByLine = if (ringing) {
                PromoCalculator.desiredNotes(rules, lines)
            } else {
                emptyMap()
            }

            for (lineItem in lineItems) {
                if (ringing) {
                    val existing = lineItem.discounts.orEmpty()
                        .filter { it.name?.startsWith(PROMO_PREFIX) == true }
                    val wanted = desiredByLine[lineItem.id].orEmpty()

                    val existingSig = existing.map { "${it.name}|${it.amount}" }.sorted()
                    val wantedSig = wanted.map { "${it.name}|${it.amountCents}" }.sorted()
                    if (existingSig != wantedSig) {
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
                }

                val wantedNote = noteByLine[lineItem.id]
                val currentNote = lineItem.note
                // Never touch a note a human wrote; only write over blank notes or our own hints.
                val canTouch = currentNote.isNullOrBlank() || PromoCalculator.isHintNote(currentNote)
                if (canTouch) {
                    val targetNote = wantedNote?.takeIf { it.isNotEmpty() }
                    if (currentNote.orEmpty() != targetNote.orEmpty()) {
                        connector.setLineItemNote(orderId, lineItem.id, targetNote)
                        Log.i(TAG, "Note on ${lineItem.id}: ${targetNote ?: "(none)"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recompute promos for order $orderId", e)
        } finally {
            connector.disconnect()
        }
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
}
