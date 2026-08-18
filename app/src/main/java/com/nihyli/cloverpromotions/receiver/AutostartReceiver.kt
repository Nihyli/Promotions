package com.nihyli.cloverpromotions.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nihyli.cloverpromotions.service.PromoMonitorService

/**
 * Starts the promo watcher after boot, install, or merchant login so
 * Register deals work without opening Promotions each time.
 */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Autostart from ${intent?.action}")
        PromoMonitorService.start(context)
    }

    companion object {
        private const val TAG = "PromoAutostart"
    }
}
