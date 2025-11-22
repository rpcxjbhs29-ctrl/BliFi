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
import androidx.core.app.ActivityCompat
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
import com.sirvivar.blifi.data.database.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

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

    // For handling replies from notifications when not connected
    private var pendingReplyMessage: String? = null
    private var pendingReplyAddress: String? = null

    // Smart notification suppression
    @Volatile
    var currentActiveChat: String? = null
        private set
        
    fun setActiveChat(address: String?) {
        currentActiveChat = address
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothChatService = this@BluetoothChatService
    }

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON. Restarting advertising and GATT server.")
                        setupGattServer()
                        startAdvertising()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF. Stopping services.")
                        // Cleanup is handled by system mostly, but we can reset flags
                        isAdvertising = false
                        bluetoothGattServer?.close()
                        bluetoothGattServer = null
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "BluetoothChatService"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.sirvivar.blifi.ACTION_REPLY"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // Handle direct reply from notification
        if (intent?.action == ACTION_REPLY) {
            Log.d(TAG, "Reply action detected")

            val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            val remoteInput = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()

            Log.d(TAG, "Reply: address=$address, text=$replyText, connected=${ChatEventBus.connectionState.value}")

            if (address != null && !replyText.isNullOrBlank()) {
                // If we're already connected, send directly
                if (ChatEventBus.connectionState.value == ConnectionState.CONNECTED) {
                    val success = sendMessage(replyText)
                    Log.d(TAG, "Reply send result: $success")

                    // Dismiss the notification since reply was processed
                    if (ActivityCompat.checkSelfPermission(this@BluetoothChatService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        val notificationId = address.hashCode()
                        NotificationManagerCompat.from(this@BluetoothChatService).cancel(notificationId)
                        Log.d(TAG, "Notification dismissed for address $address")
                    }

                    if (!success) {
                        Log.w(TAG, "Failed to send reply even when connected - may not be connected to $address")
                    }
                } else {
                    // Store the reply message to send after connection is established
                    pendingReplyMessage = replyText
                    pendingReplyAddress = address
                    Log.d(TAG, "Storing pending reply for address $address, will send after connection")

                    // Connect to the device
                    connectToDevice(address)
                }
            } else {
                Log.w(TAG, "Reply failed - address or text is null")
            }
        }
        
        startForegroundService()
        setupGattServer()
        startAdvertising()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        applyCustomDeviceName()
        createNotificationChannels()
        startPeriodicAdvertisingRestart()
        
        // Register Bluetooth state receiver
        registerReceiver(bluetoothStateReceiver, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Receiver not registered", e)
        }
        Log.d(TAG, "Service destroyed")
    }

    fun connectToDevice(deviceAddress: String) {
        Log.d(TAG, "Attempting to create a new client connection to: $deviceAddress")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        
        // If already connected to this device, don't disconnect and reconnect
        if (clientGatt?.device?.address == deviceAddress && 
            ChatEventBus.connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected to $deviceAddress, skipping reconnection")
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
        // Ensure message fits within BLE 20-byte limit
        var trimmedMessage = message
        val maxBytes = 20
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            // Truncate to maxBytes preserving UTF-8 characters
            trimmedMessage = String(bytes, 0, maxBytes, Charsets.UTF_8)
            Log.w(TAG, "Message truncated to fit BLE limit: '$trimmedMessage'")
        }
        val characteristic = clientGatt
            ?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHAT_CHARACTERISTIC_UUID)
        characteristic?.let {
            it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            it.value = trimmedMessage.toByteArray(Charsets.UTF_8)
            return if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                clientGatt?.writeCharacteristic(it) ?: false
            } else {
                false
            }
        }
        Log.e(TAG, "Failed to find characteristic or write failed")
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

                    // If we were waiting to send a pending reply but got disconnected,
                    // we should clear the pending reply
                    if (pendingReplyMessage != null) {
                        Log.w(TAG, "Connection lost while waiting to send pending reply, clearing pending reply")
                        pendingReplyMessage = null
                        pendingReplyAddress = null
                    }

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
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service not found! UUID: $SERVICE_UUID")
                return
            }

            val characteristic = service.getCharacteristic(CHAT_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                Log.d(TAG, "Found chat characteristic")
                gatt.setCharacteristicNotification(characteristic, true)
                
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    Log.d(TAG, "Found CCCD descriptor, writing enable notification value...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        Log.d(TAG, "writeDescriptor result: $result")
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        val result = gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "writeDescriptor result: $result")
                    }
                } else {
                    Log.e(TAG, "CCCD descriptor NOT found!")
                }
            } else {
                Log.w(TAG, "Characteristic not found!")
            }

            // Check if there's a pending reply to send after services are discovered
            if (pendingReplyMessage != null && pendingReplyAddress != null) {
                // Verify this is the connection we're waiting for
                if (gatt.device.address == pendingReplyAddress) {
                    val replyMessage = pendingReplyMessage
                    val replyAddress = pendingReplyAddress
                    pendingReplyMessage = null
                    pendingReplyAddress = null

                    Log.d(TAG, "Sending pending reply message after services discovered")

                    // Use a small delay to ensure everything is set up properly
                    handler.postDelayed({
                        if (ChatEventBus.connectionState.value == ConnectionState.CONNECTED) {
                            val success = sendMessage(replyMessage ?: "")
                            Log.d(TAG, "Pending reply send result: $success")

                            // Save the sent message to the database so it appears in chat history
                            if (success && replyMessage != null && replyAddress != null) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    val database = ChatDatabase.getDatabase(applicationContext)
                                    val messageEntity = MessageEntity(
                                        deviceAddress = replyAddress,
                                        text = replyMessage,
                                        isSentByUser = true,
                                        timestamp = System.currentTimeMillis(),
                                        isRead = false  // Initially mark as not read
                                    )
                                    database.messageDao().insertMessage(messageEntity)
                                    Log.d(TAG, "Saved reply message to database")
                                }
                            }

                            // Dismiss the notification since reply was processed
                            if (ActivityCompat.checkSelfPermission(this@BluetoothChatService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && replyAddress != null) {
                                val notificationId = replyAddress.hashCode()
                                NotificationManagerCompat.from(this@BluetoothChatService).cancel(notificationId)
                                Log.d(TAG, "Notification dismissed for address $replyAddress")
                            }
                        } else {
                            Log.w(TAG, "Connection lost before sending pending reply")
                        }
                    }, 300) // Small delay to ensure connection is stable
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val message = String(characteristic.value, Charsets.UTF_8)
            Log.d(TAG, "CLIENT received message: $message")
            processIncomingMessage(gatt.device, message, isServer = false)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == CCCD_UUID) {
                Log.d(TAG, "Notifications enabled. Setting connection state to CONNECTED")
                
                // Update connection state so messages can be sent
                ChatEventBus.updateConnectionState(ConnectionState.CONNECTED)
                
                // Send IAM message with device UUID and name
                val deviceUUID = Constants.getDeviceUUID(this@BluetoothChatService)
                val localName = getLocalName()
                sendMessage("ID:$deviceUUID:$localName")
            } else {
                Log.d(TAG, "onDescriptorWrite: status=$status, uuid=${descriptor?.uuid}")
            }
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
                    
                    processIncomingMessage(device, message, isServer = true)

                    if (responseNeeded) {
                        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    }
                }
            }
        }


        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "CCCD write request received from ${device.address}")
                if (responseNeeded) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            } else {
                // Respond to other descriptors if any (though we only added CCCD)
                if (responseNeeded) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
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
        
        // Explicitly add CCCD descriptor
        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        chatCharacteristic.addDescriptor(cccdDescriptor)
        
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

    private fun getLocalName(): String {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPref.getString(PREF_DEVICE_NAME, null) 
            ?: bluetoothAdapter?.name 
            ?: DEFAULT_DEVICE_NAME
    }

    private fun processIncomingMessage(device: BluetoothDevice, message: String, isServer: Boolean) {
        if (message.startsWith("ID:")) {
            val parts = message.substring(3).split(":")
            val deviceUUID: String
            val name: String

            if (parts.size >= 2) {
                // New format: "ID:UUID:Name"
                deviceUUID = parts[0]
                name = parts.subList(1, parts.size).joinToString(":").trim()
            } else {
                // Old format: "ID:Name" - use MAC address as fallback UUID
                deviceUUID = device.address
                name = parts[0].trim()
            }
            
            Log.d(TAG, "Received identity from ${device.address}: UUID=$deviceUUID, Name=$name")
            updateDeviceInfo(device.address, deviceUUID, name)
            
            // If we are the server, reply with our identity
            if (isServer) {
                val ourDeviceUUID = Constants.getDeviceUUID(this)
                val localName = getLocalName()
                val characteristic = bluetoothGattServer
                    ?.getService(SERVICE_UUID)
                    ?.getCharacteristic(CHAT_CHARACTERISTIC_UUID)
                
                characteristic?.let {
                    it.value = "ID:$ourDeviceUUID:$localName".toByteArray(Charsets.UTF_8)
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGattServer?.notifyCharacteristicChanged(device, it, false)
                    }
                }
            }
        } else {
            // Normal chat message
            saveIncomingMessage(device, message)
            
            if (isServer && device.address != ChatEventBus.activeChatAddress.value) {
                showNewMessageNotification(device.address, message)
            }
            ChatEventBus.postMessage(ChatMessage(isSentByUser = false, text = message))
        }
    }

    private fun updateDeviceInfo(address: String, deviceId: String, name: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val database = ChatDatabase.getDatabase(applicationContext)
            val sanitizedName = name.replace(Regex("[\\x00-\\x1F]"), "")
            
            // Update or insert device with the new info
            val existingDevice = database.deviceDao().getDevice(address)
            if (existingDevice != null) {
                 database.deviceDao().upsertDevice(
                     existingDevice.copy(
                         deviceId = deviceId,
                         name = sanitizedName,
                         lastSeenTimestamp = System.currentTimeMillis()
                     )
                 )
            } else {
                database.deviceDao().upsertDevice(
                    com.sirvivar.blifi.data.database.DeviceEntity(
                        address = address,
                        deviceId = deviceId,
                        name = sanitizedName,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        isOnline = true
                    )
                )
            }
        }
    }

    private fun saveIncomingMessage(deviceObj: BluetoothDevice, text: String) {
        val address = deviceObj.address
        CoroutineScope(Dispatchers.IO).launch {
            val database = ChatDatabase.getDatabase(applicationContext)
            val messageEntity = MessageEntity(
                deviceAddress = address,
                text = text,
                isSentByUser = false,
                timestamp = System.currentTimeMillis()
            )
            database.messageDao().insertMessage(messageEntity)
            
            // Also ensure device is saved/updated
            val existingDevice = database.deviceDao().getDevice(address)
            
            // Try to get name from device object, then adapter, then address
            // Sanitize name to remove nulls or invisible chars
            var name = deviceObj.name ?: bluetoothAdapter?.getRemoteDevice(address)?.name
            name = name?.trim()?.replace(Regex("[\\x00-\\x1F]"), "")
            
            if (existingDevice == null) {
                database.deviceDao().upsertDevice(
                    com.sirvivar.blifi.data.database.DeviceEntity(
                        address = address,
                        name = name ?: address,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        isOnline = true
                    )
                )
            } else {
                // Update name if we have a valid name and the existing one is just the address or null
                val newName = if (!name.isNullOrEmpty() && (existingDevice.name == address || existingDevice.name.isNullOrEmpty())) {
                    name
                } else {
                    existingDevice.name
                }
                
                database.deviceDao().upsertDevice(
                    existingDevice.copy(
                        name = newName,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }


    
    private fun showNewMessageNotification(address: String, message: String) {
        // Smart suppression: Don't notify if user is currently in this chat
        if (currentActiveChat == address) {
            Log.d(TAG, "Suppressing notification - user is active in chat: $address")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val database = ChatDatabase.getDatabase(applicationContext)
            val device = database.deviceDao().getDevice(address)
            val senderName = device?.name ?: address
            val deviceId = device?.deviceId ?: address

            // Query unread messages for conversation history
            val unreadMessages = database.messageDao().getUnreadMessagesForDevice(address).first()
            Log.d(TAG, "Notification: Found ${unreadMessages.size} unread messages for address=$address")

            val intent = Intent(this@BluetoothChatService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_DEVICE_ADDRESS", address)
            }

            val pendingIntent = PendingIntent.getActivity(
                this@BluetoothChatService,
                address.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Create RemoteInput for direct reply
            val remoteInput = androidx.core.app.RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("Reply to $senderName")
                .build()

            // Create reply intent
            val replyIntent = Intent(this@BluetoothChatService, BluetoothChatService::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }

            val replyPendingIntent = PendingIntent.getService(
                this@BluetoothChatService,
                address.hashCode() + 1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Create reply action
            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()

            // Create Person objects
            val sender = androidx.core.app.Person.Builder()
                .setName(senderName)
                .setKey(address)
                .build()

            val me = androidx.core.app.Person.Builder()
                .setName("You")
                .setKey("me")
                .build()

            // Create MessagingStyle with conversation history
            val messagingStyle = NotificationCompat.MessagingStyle(me)
                .setConversationTitle(senderName)

            // Add unread messages to show context
            // Note: The incoming message should already be in unreadMessages if it was just saved
            unreadMessages.forEach { msgEntity ->
                val person = if (msgEntity.isSentByUser) me else sender
                messagingStyle.addMessage(msgEntity.text, msgEntity.timestamp, person)
            }

            val notification = NotificationCompat.Builder(this@BluetoothChatService, MESSAGE_CHANNEL_ID)
                .setStyle(messagingStyle)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setColor(0x0A84FF) // BliFi Blue
                .addAction(replyAction) // Direct reply action
                .setShortcutId(address) // Critical for conversation threading
                .build()

            if (ActivityCompat.checkSelfPermission(this@BluetoothChatService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Use address hash as notification ID - this ensures same conversation gets same ID
                val notificationId = address.hashCode()
                NotificationManagerCompat.from(this@BluetoothChatService).notify(notificationId, notification)
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