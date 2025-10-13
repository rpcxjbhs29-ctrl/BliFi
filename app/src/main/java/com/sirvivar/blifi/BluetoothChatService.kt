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

    companion object {
        private const val CHANNEL_ID = "bli_fi_chat_channel"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "BluetoothChatService"
        val CHAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a3d-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupGattServer()
        startAdvertising()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BliFi Chat Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BliFi Chat Running")
            .setContentText("BLE chat service in background")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupGattServer() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

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
    }

    private fun startAdvertising() {
        if (isAdvertising || bluetoothAdapter == null) return

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            stopSelf()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            stopSelf()
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices[device.address] = device
                Log.d(TAG, "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device.address)
                Log.d(TAG, "Device disconnected: ${device.address}")
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
                value?.let {
                    val message = String(it)
                    Handler(Looper.getMainLooper()).post {
                        messageCallbacks.forEach { callback ->
                            callback(device.address, message)
                        }
                    }
                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            }
        }
    }

    fun sendMessage(deviceAddress: String, message: String): Boolean {
        val device = connectedDevices[deviceAddress] ?: return false
        val service = bluetoothGattServer?.getService(MainActivity.SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(CHAT_CHARACTERISTIC_UUID) ?: return false

        characteristic.setValue(message.toByteArray())
        return bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
    }

    fun registerMessageCallback(callback: (String, String) -> Unit) {
        messageCallbacks.add(callback)
    }

    fun unregisterMessageCallback(callback: (String, String) -> Unit) {
        messageCallbacks.remove(callback)
    }

    override fun onDestroy() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        bluetoothGattServer?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}