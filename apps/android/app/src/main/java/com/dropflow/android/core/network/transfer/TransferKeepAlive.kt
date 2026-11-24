package com.dropflow.android.core.network.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dropflow.android.R

private const val TAG = "DropFlowKeepAlive"

/**
 * Keeps the radio and process awake for exactly one active transfer.
 *
 * Phones power-save Wi-Fi aggressively and freeze background apps; both stall a
 * long transfer. While a transfer slot is held we take a Wi-Fi performance lock,
 * a bounded partial wake lock, and (when Android allows it) a dataSync
 * foreground service with an ongoing notification. Nothing changes the user's
 * network: the locks only affect power management of the current connection.
 */
internal class TransferKeepAlive(context: Context) {
    private val appContext = context.applicationContext
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceStarted = false

    @Synchronized
    fun acquire() {
        if (wakeLock != null) return
        wifiLocks = createWifiLocks().onEach { lock ->
            runCatching { lock.acquire() }.onFailure { Log.w(TAG, "Wi-Fi lock unavailable", it) }
        }
        wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropFlow:transfer")
            ?.apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        serviceStarted = runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, TransferService::class.java))
            true
        }.getOrElse {
            // Android 12+ refuses to start a foreground service from the background
            // (e.g. an incoming transfer while the app isn't visible). The locks still apply.
            Log.i(TAG, "Foreground service not started: ${it.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun release() {
        wifiLocks.forEach { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLocks = emptyList()
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        if (serviceStarted) appContext.stopService(Intent(appContext, TransferService::class.java))
        serviceStarted = false
    }

    @SuppressLint("WifiManagerLeak")
    @Suppress("DEPRECATION")
    private fun createWifiLocks(): List<WifiManager.WifiLock> {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        // HIGH_PERF disables Wi-Fi power save on most devices; LOW_LATENCY (API 29+)
        // applies while the screen is on and the app is foreground. Hold both.
        val locks = mutableListOf(
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DropFlow:transfer-perf")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            locks += wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DropFlow:transfer-latency")
        }
        locks.forEach { it.setReferenceCounted(false) }
        return locks
    }

    private companion object {
        /** Safety net if a release is ever missed; a transfer that long restarts the lock. */
        const val MAX_HOLD_MS = 4 * 60 * 60 * 1000L
    }
}

/** Ongoing "Transferring files" notification that keeps the process eligible to run. */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while files are moving"
                setShowBadge(false)
            }
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("Transferring files")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let {
                android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
            })
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForeground refused", it)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "transfers"
        const val NOTIFICATION_ID = 4210
    }
}
