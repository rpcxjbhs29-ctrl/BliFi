package com.sirvivar.blifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID

class BluetoothChatService : Service() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val messageCallbacks = mutableListOf<(String, String) -> Unit>()

    // For binding (ChatActivity calls sendMessage)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothChatService = this@BluetoothChatService
    }

    companion object {
        private const val CHANNEL_ID = "bli_fi_chat_channel"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "BluetoothChatService"
        val CHAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FEE1-0000-1000-8000-00805F9B34FB") // Custom for chat
    }

    override fun onCreate() {
        super.onCreate()
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "onCreate() started at $startTime")

        // 1. Create the notification channel FIRST. This is the crucial fix.
        createNotificationChannel()

        // 2. Create a single, valid notification that uses the correct channel ID.
        val notification = createNotification()

        // 3. Start the service in the foreground.
        startForeground(NOTIFICATION_ID, notification)

        // Defer GATT/advertise to avoid blocking the main thread
        Handler(Looper.getMainLooper()).post {
            setupGattServer()
            startAdvertising()
            Log.d(TAG, "GATT/Advertise setup at ${System.currentTimeMillis() - startTime}ms total")
        }

        Log.d(TAG, "onCreate() base complete at ${System.currentTimeMillis() - startTime}ms")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BliFi Chat Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BliFi Chat Running")
            .setContentText("GATT ready for connections")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun setupGattServer() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            stopSelf()
            return
        }
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        if (bluetoothGattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            stopSelf()
            return
        }
        Log.d(TAG, "GATT server opened")

        val service = BluetoothGattService(
            MainActivity.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val chatCharacteristic = BluetoothGattCharacteristic(
            CHAT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(chatCharacteristic)
        bluetoothGattServer?.addService(service)
        Log.d(TAG, "GATT service added - ready for connections")
    }

    private fun startAdvertising() {
        if (isAdvertising || bluetoothAdapter == null || advertiser != null) return

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "Failed to get advertiser")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)  // Enable GATT connects
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))  // For discovery
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
        Log.d(TAG, "Advertising started for GATT discovery")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising success")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices[device.address] = device
                    Log.d(TAG, "GATT connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    Log.d(TAG, "GATT disconnected: ${device.address}")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == CHAT_CHARACTERISTIC_UUID) {
                value?.let { bytes ->
                    val message = String(bytes)
                    Log.d(TAG, "GATT write received: $message from ${device.address}")
                    Handler(Looper.getMainLooper()).post {
                        messageCallbacks.forEach { it(device.address, message) }
                    }
                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAT_CHARACTERISTIC_UUID) {
                val value = characteristic.value ?: byteArrayOf()
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                Log.d(TAG, "GATT read request from ${device.address}")
            }
        }
    }

    // GATT send: Notify to connected device
    fun sendMessage(deviceAddress: String, message: String): Boolean {
        val device = connectedDevices[deviceAddress] ?: run {
            Log.w(TAG, "No connection to $deviceAddress")
            return false
        }
        val service = bluetoothGattServer?.getService(MainActivity.SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(CHAT_CHARACTERISTIC_UUID) ?: return false

        characteristic.value = message.toByteArray()
        val success = bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
        if (success) Log.d(TAG, "GATT notify sent: $message to $deviceAddress")
        return success
    }

    fun registerMessageCallback(callback: (String, String) -> Unit) {
        messageCallbacks.add(callback)
    }

    fun unregisterMessageCallback(callback: (String, String) -> Unit) {
        messageCallbacks.remove(callback)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        bluetoothGattServer?.close()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}