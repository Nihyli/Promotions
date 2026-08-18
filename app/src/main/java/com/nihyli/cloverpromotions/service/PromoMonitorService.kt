package com.nihyli.cloverpromotions.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.clover.sdk.util.CloverAccount
import com.clover.sdk.v1.Intents
import com.clover.sdk.v3.order.OrderConnector
import com.clover.sdk.v3.order.OrderV31Connector
import com.nihyli.cloverpromotions.MainActivity
import com.nihyli.cloverpromotions.R
import com.nihyli.cloverpromotions.engine.PromoEngine
import com.nihyli.cloverpromotions.receiver.PromoBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Stays running so we can hear Register cart changes.
 *
 * Android 8+ blocks implicit broadcasts (LINE_ITEM_ADDED, etc.) from waking a
 * manifest-registered receiver while the app is in the background. A dynamically
 * registered receiver in a foreground service still gets them.
 */
class PromoMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiver = PromoBroadcastReceiver()
    private var orderConnector: OrderConnector? = null

    private val orderListener = object : OrderV31Connector.OnOrderUpdateListener2 {
        override fun onOrderUpdated(orderId: String, selfChange: Boolean) {
            if (!selfChange) recompute(orderId)
        }
        override fun onOrderCreated(orderId: String) = recompute(orderId)
        override fun onOrderDeleted(orderId: String) {}
        override fun onOrderDiscountAdded(orderId: String, discountId: String) {}
        override fun onOrderDiscountsDeleted(orderId: String, discountIds: MutableList<String>) {}
        override fun onLineItemsAdded(orderId: String, lineItemIds: MutableList<String>) = recompute(orderId)
        override fun onLineItemsUpdated(orderId: String, lineItemIds: MutableList<String>) = recompute(orderId)
        override fun onLineItemsDeleted(orderId: String, lineItemIds: MutableList<String>) = recompute(orderId)
        override fun onLineItemModificationsAdded(
            orderId: String,
            lineItemIds: MutableList<String>,
            modificationIds: MutableList<String>,
        ) {}
        override fun onLineItemDiscountsAdded(
            orderId: String,
            lineItemIds: MutableList<String>,
            discountIds: MutableList<String>,
        ) {}
        override fun onLineItemExchanged(orderId: String, oldLineItemId: String, newLineItemId: String) = recompute(orderId)
        override fun onPaymentProcessed(orderId: String, paymentId: String) = recompute(orderId)
        override fun onRefundProcessed(orderId: String, refundId: String) {}
        override fun onCreditProcessed(orderId: String, creditId: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intents.ACTION_LINE_ITEM_ADDED)
            addAction(Intents.ACTION_LINE_ITEM_DELETED)
            addAction(Intents.ACTION_ORDER_CREATED)
        }
        registerReceiver(receiver, filter)
        attachOrderListener()
        Log.i(TAG, "Promo monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachOrderListener()
        return START_STICKY
    }

    private fun attachOrderListener() {
        if (orderConnector != null) return
        val account = CloverAccount.getAccount(this)
        if (account == null) {
            Log.w(TAG, "No Clover account yet; will retry when started again")
            return
        }
        orderConnector = OrderConnector(this, account, null).also { connector ->
            connector.connect()
            connector.addOnOrderChangedListener(orderListener)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        orderConnector?.removeOnOrderChangedListener(orderListener)
        orderConnector?.disconnect()
        orderConnector = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recompute(orderId: String?) {
        if (orderId.isNullOrBlank()) return
        scope.launch {
            try {
                PromoEngine.recompute(applicationContext, orderId)
            } catch (e: Exception) {
                Log.e(TAG, "recompute failed for $orderId", e)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Promotions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Watches the Register cart for promotions" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Watching Register for promotions")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PromoMonitor"
        private const val CHANNEL_ID = "promotions"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, PromoMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
