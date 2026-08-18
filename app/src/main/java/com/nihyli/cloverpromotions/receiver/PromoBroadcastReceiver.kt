package com.nihyli.cloverpromotions.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clover.sdk.v1.Intents
import com.nihyli.cloverpromotions.engine.PromoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Woken by Clover whenever the Register cart changes; recomputes promotions
 * for the affected order.
 */
class PromoBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(Intents.EXTRA_CLOVER_ORDER_ID) ?: return
        Log.d(TAG, "Received ${intent.action} for order $orderId")

        val pendingResult = goAsync()
        scope.launch {
            try {
                PromoEngine.recompute(context.applicationContext, orderId)
            } catch (e: Exception) {
                Log.e(TAG, "Promo recompute failed for order $orderId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PromoReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
