package com.sirvivar.blifi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sirvivar.blifi.ui.ChatEventBus
import com.sirvivar.blifi.ui.ChatMessage
import java.util.UUID

class BluetoothChatService : Service() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothChatService = this@BluetoothChatService
    }

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "bli_fi_chat_channel"
        private const val MESSAGE_CHANNEL_ID = "bli_fi_message_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 2
        private const val MESSAGE_NOTIFICATION_ID = 3
        private const val TAG = "BluetoothChatService"
        val CHAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FEE1-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundService()
        setupGattServer()
        startAdvertising()
    }

    private fun createNotificationChannels() {
        // This is safe to call multiple times; channels are only created once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "BliFi Chat Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(foregroundChannel)

            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "New Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(messageChannel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("BliFi Chat Running")
            .setContentText("Ready for connections")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your app's icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun setupGattServer() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        if (bluetoothGattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            stopSelf()
            return
        }

        val service = BluetoothGattService(MainActivity.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val chatCharacteristic = BluetoothGattCharacteristic(
            CHAT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val configDescriptor = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        chatCharacteristic.addDescriptor(configDescriptor)
        service.addCharacteristic(chatCharacteristic)
        bluetoothGattServer?.addService(service)
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.d(TAG, "Advertising success") }
        override fun onStartFailure(errorCode: Int) { Log.e(TAG, "Advertising failed: $errorCode") }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // No changes needed here
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic.uuid == CHAT_CHARACTERISTIC_UUID) {
                value?.let {
                    val message = String(it, Charsets.UTF_8)
                    Log.d(TAG, "GATT write received: '$message' from ${device.address}")

                    // Check if the chat screen for this sender is NOT open
                    if (device.address != ChatEventBus.activeChatAddress.value) {
                        showNewMessageNotification(device.address, message)
                    }

                    // Always post the event so the UI can update if it is open
                    ChatEventBus.postEvent(ChatMessage(device.address, message))

                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == CCCD_UUID && responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun showNewMessageNotification(senderAddress: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Flag is required for Android 6.0+
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flag)

        val builder = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_bubble) // Make sure you add this icon
            .setContentTitle("New message from $senderAddress")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            try {
                notify(MESSAGE_NOTIFICATION_ID, builder.build())
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to show notification due to missing permission.", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        advertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()
        Log.d(TAG, "Service destroyed")
    }
}