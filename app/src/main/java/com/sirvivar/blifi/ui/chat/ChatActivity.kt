package com.sirvivar.blifi.ui.chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.BluetoothChatService
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.R

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

    // Service binding for GATT send/receive
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
                        Log.d("ChatActivity", "GATT received: $message")
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
                    Log.d("ChatActivity", "GATT services discovered, notifications enabled")
                } else {
                    Log.e("ChatActivity", "Chat characteristic not found")
                    Toast.makeText(this@ChatActivity, "Chat service not available", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("ChatActivity", "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic?.uuid == BluetoothChatService.CHAT_CHARACTERISTIC_UUID) {
                val message = characteristic.getStringValue(0)
                runOnUiThread {
                    messages.add(Pair(false, message))
                    chatAdapter?.notifyDataSetChanged()
                    recyclerView?.smoothScrollToPosition(messages.size - 1)
                    Log.d("ChatActivity", "GATT characteristic changed: $message")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatActivity", "GATT write success")
            } else {
                Log.e("ChatActivity", "GATT write failed: $status")
                Toast.makeText(this@ChatActivity, "Failed to send message", Toast.LENGTH_SHORT).show()
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
                deviceAddress?.let { address ->
                    // Prefer service-bound GATT send
                    if (isBound && bluetoothChatService?.sendMessage(address, message) == true) {
                        messages.add(Pair(true, message))
                        chatAdapter?.notifyDataSetChanged()
                        recyclerView?.smoothScrollToPosition(messages.size - 1)
                        messageInput.text.clear()
                        Log.d("ChatActivity", "GATT message sent via service: $message")
                        return@let
                    }
                    // Fallback: Direct GATT client write
                    val service = bluetoothGatt?.getService(MainActivity.SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        characteristic.value = message.toByteArray()
                        bluetoothGatt?.writeCharacteristic(characteristic)
                        messages.add(Pair(true, message))
                        chatAdapter?.notifyDataSetChanged()
                        recyclerView?.smoothScrollToPosition(messages.size - 1)
                        messageInput.text.clear()
                        Log.d("ChatActivity", "GATT fallback write: $message")
                    } else {
                        Log.e("ChatActivity", "No GATT characteristic")
                        Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Bind to GATT service
        val serviceIntent = Intent(this, BluetoothChatService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        // Start GATT connection
        connectToDevice()
    }

    private fun connectToDevice() {
        deviceAddress?.let { address ->
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
            Log.d("ChatActivity", "GATT connect attempt to $address (try $connectionAttempts)")
        }
    }

    override fun onStart() {
        super.onStart()
        // Service already bound in onCreate
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            bluetoothChatService?.unregisterMessageCallback { address, _ -> address == deviceAddress }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
            bluetoothChatService = null
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        recyclerView = null
        chatAdapter = null
        Log.d("ChatActivity", "Destroyed")
    }
}