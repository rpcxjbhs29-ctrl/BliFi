package com.sirvivar.blifi.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sirvivar.blifi.R
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.utils.ChatEventBus
import com.sirvivar.blifi.data.model.ChatMessage
import com.sirvivar.blifi.data.model.ConnectionState
import com.sirvivar.blifi.utils.Constants
import com.sirvivar.blifi.utils.Constants.CCCD_UUID
import com.sirvivar.blifi.utils.Constants.CHAT_CHARACTERISTIC_UUID
import com.sirvivar.blifi.utils.Constants.DEFAULT_DEVICE_NAME
import com.sirvivar.blifi.utils.Constants.FOREGROUND_CHANNEL_ID
import com.sirvivar.blifi.utils.Constants.FOREGROUND_NOTIFICATION_ID
import com.sirvivar.blifi.utils.Constants.LAST_DEVICE_ADDRESS
import com.sirvivar.blifi.utils.Constants.MESSAGE_CHANNEL_ID
import com.sirvivar.blifi.utils.Constants.MESSAGE_NOTIFICATION_ID
import com.sirvivar.blifi.utils.Constants.PREF_DEVICE_NAME
import com.sirvivar.blifi.utils.Constants.PREFS_NAME
import com.sirvivar.blifi.utils.Constants.SERVICE_UUID
import com.sirvivar.blifi.data.database.ChatDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ❌ The duplicate ChatAdapter class has been REMOVED from this file.

class BluetoothChatService : Service() {
    // ... all the service code from the previous step ...
    // The content of this file is identical to the last one I sent you,
    // just make sure the package line at the top is exactly as shown above.

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var clientGatt: BluetoothGatt? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var advertisingRestartRunnable: Runnable? = null
    private var isAdvertising = false

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothChatService = this@BluetoothChatService
    }

    companion object {
        private const val TAG = "BluetoothChatService"
    }

    override fun onCreate() {
        super.onCreate()
        applyCustomDeviceName()
        createNotificationChannels()
        startForegroundService()
        setupGattServer()
        startAdvertising()
        startPeriodicAdvertisingRestart()
        // Removed reconnectToLastDevice() as per instructions
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicAdvertisingRestart()
        disconnectClient()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        bluetoothGattServer?.close()
        Log.d(TAG, "Service destroyed")
    }

    fun connectToDevice(deviceAddress: String) {
        Log.d(TAG, "Attempting to create a new client connection to: $deviceAddress")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        ChatEventBus.updateConnectionState(ConnectionState.CONNECTING)
        clientGatt?.close()
        saveLastDeviceAddress(deviceAddress)
        reconnectAttempts = 0  // Reset counter on new user-initiated connection
        clientGatt = device?.connectGatt(this, false, clientGattCallback)
    }

    fun disconnectClient() {
        val lastAddress = getLastDeviceAddress()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            clientGatt?.disconnect()
            clientGatt?.close()
        }
        clientGatt = null
        ChatEventBus.updateConnectionState(ConnectionState.DISCONNECTED)
        // Update device to offline when disconnecting
        lastAddress?.let {
            ChatEventBus.updateDeviceStatus(it, isOnline = false)
        }
        saveLastDeviceAddress(null)
    }

    fun sendMessage(message: String): Boolean {
        if (ChatEventBus.connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message, not connected.")
            return false
        }
        val characteristic = clientGatt
            ?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHAT_CHARACTERISTIC_UUID)
        characteristic?.let {
            it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            it.value = message.toByteArray(Charsets.UTF_8)
            return if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                clientGatt?.writeCharacteristic(it) ?: false
            } else {
                false
            }
        }
        return false
    }

    // Removed reconnectToLastDevice() as per instructions

    private fun saveLastDeviceAddress(address: String?) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(LAST_DEVICE_ADDRESS, address)
            apply()
        }
    }

    private fun getLastDeviceAddress(): String? {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getString(LAST_DEVICE_ADDRESS, null)
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "CLIENT connected to GATT server successfully.")
                        ChatEventBus.updateConnectionState(ConnectionState.CONNECTED)
                        ChatEventBus.updateDeviceStatus(gatt.device.address, isOnline = true)
                        gatt.discoverServices()
                    } else {
                        Log.e(TAG, "CLIENT connected but with error status: $status")
                        ChatEventBus.updateConnectionState(ConnectionState.DISCONNECTED)
                        gatt.close()
                        clientGatt = null
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "CLIENT disconnected from GATT server. Status: $status")
                    ChatEventBus.updateConnectionState(ConnectionState.DISCONNECTED)
                    ChatEventBus.updateDeviceStatus(gatt.device.address, isOnline = false)
                    gatt.close()
                    clientGatt = null
                    
                    // If disconnected due to error, log it
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Disconnected with error status: $status (${getGattStatusMessage(status)})")
                    }
                    
                    // Do NOT auto-reconnect - causes infinite loop
                    // User can manually tap the device again to reconnect
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "CLIENT is connecting...")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAT_CHARACTERISTIC_UUID)
            characteristic?.let {
                gatt.setCharacteristicNotification(it, true)
                val descriptor = it.getDescriptor(CCCD_UUID)
                descriptor?.let { desc ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val message = String(characteristic.value, Charsets.UTF_8)
            Log.d(TAG, "CLIENT received message: $message")
            ChatEventBus.postMessage(ChatMessage(isSentByUser = false, text = message))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CLIENT message sent successfully.")
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic.uuid == CHAT_CHARACTERISTIC_UUID) {
                value?.let {
                    val message = String(it, Charsets.UTF_8)
                    Log.d(TAG, "SERVER received message: '$message' from ${device.address}")
                    if (device.address != ChatEventBus.activeChatAddress.value) {
                        showNewMessageNotification(device.address, message)
                    }
                    ChatEventBus.postMessage(ChatMessage(isSentByUser = false, text = message))
                    if (responseNeeded) {
                        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannels() {
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun setupGattServer() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        if (bluetoothGattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            stopSelf()
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val chatCharacteristic = BluetoothGattCharacteristic(
            CHAT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(chatCharacteristic)
        bluetoothGattServer?.addService(service)
    }

    private fun startAdvertising() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "Failed to create advertiser")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
            isAdvertising = true
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            isAdvertising = false
            
            // Retry after delay
            handler.postDelayed({
                Log.d(TAG, "Retrying advertising after failure...")
                restartAdvertising()
            }, 5000) // Retry after 5 seconds
        }
    }

    private fun showNewMessageNotification(senderAddress: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val database = ChatDatabase.getDatabase(applicationContext)
            val device = database.deviceDao().getDevice(senderAddress)
            val senderName = device?.name ?: senderAddress

            val intent = Intent(this@BluetoothChatService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this@BluetoothChatService, 0, intent, flag)

            val builder = NotificationCompat.Builder(this@BluetoothChatService, MESSAGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_bubble)
                .setContentTitle(senderName)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this@BluetoothChatService).notify(MESSAGE_NOTIFICATION_ID, builder.build())
            }
        }
    }
    
    private fun applyCustomDeviceName() {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customName = sharedPref.getString(PREF_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothAdapter?.name = customName
                Log.d(TAG, "Device name set to: $customName")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to set device name", e)
            }
        }
    }
    
    private fun startPeriodicAdvertisingRestart() {
        advertisingRestartRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "Periodic advertising restart triggered")
                restartAdvertising()
                // Schedule next restart in 3 minutes
                handler.postDelayed(this, 180000) // 3 minutes
            }
        }
        // Start the periodic restart
        handler.postDelayed(advertisingRestartRunnable!!, 180000)
        Log.d(TAG, "Periodic advertising restart scheduled")
    }
    
    private fun stopPeriodicAdvertisingRestart() {
        advertisingRestartRunnable?.let {
            handler.removeCallbacks(it)
            advertisingRestartRunnable = null
            Log.d(TAG, "Periodic advertising restart stopped")
        }
    }
    
    private fun restartAdvertising() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot restart advertising: permission not granted")
            return
        }
        
        try {
            // Stop current advertising
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                Log.d(TAG, "Stopped advertising for restart")
            }
            
            // Small delay before restarting
            handler.postDelayed({
                startAdvertising()
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting advertising", e)
        }
    }
    
    private fun getGattStatusMessage(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
            133 -> "GATT_ERROR (Device not reachable)"
            8 -> "Connection timeout"
            19 -> "Connection terminated by peer"
            22 -> "Connection terminated locally"
            else -> "Unknown error ($status)"
        }
    }
}