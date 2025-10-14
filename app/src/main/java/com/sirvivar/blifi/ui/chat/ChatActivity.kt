package com.sirvivar.blifi.ui.chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.BluetoothChatService
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.R
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private var recyclerView: RecyclerView? = null
    private var chatAdapter: ChatAdapter? = null
    private val messages = mutableListOf<Pair<Boolean, String>>()
    private var bluetoothChatService: BluetoothChatService? = null
    private var isBound = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private var deviceName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 3

    // --- START OF FIX 1: ADD STATE VARIABLE ---
    @Volatile
    private var isReadyToSend = false
    // --- END OF FIX 1 ---

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothChatService.LocalBinder
            bluetoothChatService = binder.getService()
            isBound = true
            bluetoothChatService?.registerMessageCallback { address, message ->
                if (address == deviceAddress) {
                    runOnUiThread {
                        messages.add(Pair(false, message))
                        chatAdapter?.notifyDataSetChanged()
                        recyclerView?.smoothScrollToPosition(messages.size - 1)
                        Log.d("ChatActivity", "Message received via service callback: $message")
                    }
                }
            }
            Log.d("ChatActivity", "Service bound - GATT ready")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothChatService = null
            Log.d("ChatActivity", "Service unbound")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("ChatActivity", "GATT connected to $deviceAddress")
                    Toast.makeText(this@ChatActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                    gatt?.discoverServices()
                    connectionAttempts = 0
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // --- START OF FIX 2: RESET STATE ON DISCONNECT ---
                    isReadyToSend = false
                    // --- END OF FIX 2 ---
                    Log.e("ChatActivity", "GATT disconnected from $deviceAddress, status: $status")
                    Toast.makeText(this@ChatActivity, "Disconnected. Retrying...", Toast.LENGTH_SHORT).show()
                    if (status == 133 && connectionAttempts < maxConnectionAttempts) {
                        connectionAttempts++
                        handler.postDelayed({ connectToDevice() }, 500)
                    } else {
                        finish()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(MainActivity.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    val descriptor = characteristic.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                        Log.d("ChatActivity", "GATT services discovered, CCCD descriptor write initiated")
                    } else {
                        Log.e("ChatActivity", "CCCD descriptor not found for chat characteristic")
                    }
                } else {
                    Log.e("ChatActivity", "Chat characteristic not found")
                }
            } else {
                Log.e("ChatActivity", "Service discovery failed: $status")
            }
        }

        // --- START OF FIX 3: IMPLEMENT ON DESCRIPTOR WRITE ---
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatActivity", "CCCD descriptor write success. Ready to send messages.")
                isReadyToSend = true
            } else {
                Log.e("ChatActivity", "CCCD descriptor write failed: $status")
            }
        }
        // --- END OF FIX 3 ---

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            // This is a backup way to receive messages. The service callback is the primary way.
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatActivity", "GATT write successful")
            } else {
                Log.e("ChatActivity", "GATT write failed with status: $status")
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, "Message failed to send", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        deviceName = intent.getStringExtra("DEVICE_NAME")
        title = "Chat with $deviceName"

        recyclerView = findViewById(R.id.recycler_chat)
        recyclerView?.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        recyclerView?.adapter = chatAdapter

        val messageInput: EditText = findViewById(R.id.message_input)
        val sendButton: Button = findViewById(R.id.send_button)

        sendButton.setOnClickListener {
            val message = messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                // --- START OF FIX 4: CHECK IF READY BEFORE SENDING ---
                if (!isReadyToSend) {
                    Toast.makeText(this, "Connection not fully ready, please wait", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // --- END OF FIX 4 ---

                val service = bluetoothGatt?.getService(MainActivity.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = message.toByteArray(Charsets.UTF_8)
                    val success = bluetoothGatt?.writeCharacteristic(characteristic) == true

                    if (success) {
                        Log.d("ChatActivity", "GATT write initiated for: $message")
                        messages.add(Pair(true, message))
                        chatAdapter?.notifyDataSetChanged()
                        recyclerView?.smoothScrollToPosition(messages.size - 1)
                        messageInput.text.clear()
                    } else {
                        Log.e("ChatActivity", "GATT write failed locally")
                        Toast.makeText(this, "Failed to send", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e("ChatActivity", "GATT characteristic not found for writing.")
                    Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val serviceIntent = Intent(this, BluetoothChatService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        connectToDevice()
    }

    private fun connectToDevice() {
        deviceAddress?.let { address ->
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
            Log.d("ChatActivity", "GATT connect attempt to $address (try $connectionAttempts)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothChatService?.unregisterMessageCallback { address, _ -> address == deviceAddress }
            unbindService(connection)
            isBound = false
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        recyclerView = null
        chatAdapter = null
        Log.d("ChatActivity", "Destroyed")
    }
}