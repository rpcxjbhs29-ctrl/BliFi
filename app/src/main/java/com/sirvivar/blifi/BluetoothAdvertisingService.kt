package com.sirvivar.blifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class BluetoothAdvertisingService : Service() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private val handler = Handler(Looper.getMainLooper())
    private var retryAttempts = 0
    private val maxRetries = 3
    private var pendingMessage: String? = null

    companion object {
        private const val CHANNEL_ID = "bli_fi_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "BluetoothAdvertisingService"
        private const val MANUFACTURER_ID = 0xFFFF  // Fixed: Int for addManufacturerData (custom app ID)
        private const val MAX_AD_BYTES = 18  // Safe limit for mfg data in ads (UTF-8 chars)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Service ready"))
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser unavailable—stopping")
            stopSelf()
            return
        }
        Log.d(TAG, "BLE advertiser initialized")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BliFi Broadcasts",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "BLE message sharing" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BliFi Broadcasting")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)  // Use your app's foreground icon (or @android:drawable/ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // Public API: Call from ChatActivity
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            Log.w(TAG, "Blank message—skipping")
            return
        }
        Log.d(TAG, "Queued message: '$message' (${message.length} chars)")
        pendingMessage = message
        startSingleAdvertising()
    }

    private fun startSingleAdvertising() {
        // Always stop first to avoid error 1 (already started)
        if (isAdvertising) {
            Log.d(TAG, "Stopping active ad before restart")
            stopAdvertising()
        }

        pendingMessage?.let { msg ->
            // Safe encoding—no index crash, pad to fixed size
            val trimmed = msg.take(MAX_AD_BYTES)
            val padded = trimmed.padEnd(MAX_AD_BYTES, ' ')  // Pad with spaces for fixed 18 bytes
            val bytes = padded.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_AD_BYTES) {
                Log.w(TAG, "UTF-8 expanded—truncating to ${MAX_AD_BYTES} bytes")
                // Slice if multi-byte chars push over (rare)
            }

            val data = AdvertiseData.Builder()
                .addManufacturerData(MANUFACTURER_ID, bytes)  // Payload in mfg data (parse on receiver)
                .setIncludeDeviceName(false)  // Save bytes
                .setIncludeTxPowerLevel(false)
                .build()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)  // Broadcast only (like bitchat discovery)
                .setTimeout(10000)  // 10s burst to save battery
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)  // Balanced range/power
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            Log.d(TAG, "Started ad with: '$msg'")
            updateNotification("Broadcasting: $msg")
        } ?: Log.w(TAG, "No message to advertise")
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        handler.removeCallbacksAndMessages(null)
        retryAttempts = 0
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settings: AdvertiseSettings?) {
            Log.d(TAG, "Ad started OK")
            updateNotification("Active: Broadcasting")
            pendingMessage = null  // Clear after success
            retryAttempts = 0
        }

        override fun onStartFailure(error: Int) {
            isAdvertising = false
            Log.e(TAG, "Ad failed: $error")
            when (error) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> {
                    if (retryAttempts < maxRetries) {
                        retryAttempts++
                        Log.w(TAG, "Retry $retryAttempts/$maxRetries in 2s")
                        handler.postDelayed({ startSingleAdvertising() }, 2000)
                    } else {
                        Log.e(TAG, "Max retries—giving up")
                        pendingMessage = null
                    }
                }
                else -> {
                    Log.e(TAG, "Unhandled error $error—stopping")
                    pendingMessage = null
                }
            }
            updateNotification("Failed (error $error)")
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notif = createNotification(text)
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {  // Catch IllegalArg (icon/missing) or others
            Log.e(TAG, "Notify update failed: ${e.message}—logging only")
        }
    }

    override fun onDestroy() {
        stopAdvertising()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null  // Not bound; start via Intent
}