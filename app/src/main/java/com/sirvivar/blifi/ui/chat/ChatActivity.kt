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
    @Volatile
    private var isReadyToSend = false

    // --- START OF FIX 1: DEFINE THE CALLBACK AS A VARIABLE ---
    private val messageCallback: (String, String) -> Unit = { address, message ->
        if (address == deviceAddress) {
            runOnUiThread {
                messages.add(Pair(false, message))
                chatAdapter?.notifyDataSetChanged()
                recyclerView?.smoothScrollToPosition(messages.size - 1)
                Log.d("ChatActivity", "Message received and UI updated: $message")
            }
        }
    }
    // --- END OF FIX 1 ---

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothChatService.LocalBinder
            bluetoothChatService = binder.getService()
            isBound = true

            // --- START OF FIX 2: REGISTER THE PRE-DEFINED CALLBACK ---
            bluetoothChatService?.registerMessageCallback(messageCallback)
            // --- END OF FIX 2 ---

            Log.d("ChatActivity", "Service bound and message callback registered.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothChatService?.unregisterMessageCallback(messageCallback) // Good practice to unregister here too
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
                    handler.post { gatt?.discoverServices() }
                    connectionAttempts = 0
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isReadyToSend = false
                    Log.e("ChatActivity", "GATT disconnected from $deviceAddress, status: $status")
                    Toast.makeText(this@ChatActivity, "Disconnected.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(MainActivity.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(BluetoothChatService.CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatActivity", "CCCD descriptor write success. Ready to send messages.")
                isReadyToSend = true
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatActivity", "GATT write successful")
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
                if (!isReadyToSend) {
                    Toast.makeText(this, "Connection not fully ready", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val characteristic = bluetoothGatt
                    ?.getService(MainActivity.SERVICE_UUID)
                    ?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = message.toByteArray(Charsets.UTF_8)
                    if (bluetoothGatt?.writeCharacteristic(characteristic) == true) {
                        messages.add(Pair(true, message))
                        chatAdapter?.notifyDataSetChanged()
                        recyclerView?.smoothScrollToPosition(messages.size - 1)
                        messageInput.text.clear()
                    }
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            // --- START OF FIX 3: UNREGISTER THE SAME CALLBACK INSTANCE ---
            bluetoothChatService?.unregisterMessageCallback(messageCallback)
            // --- END OF FIX 3 ---
            unbindService(connection)
            isBound = false
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        Log.d("ChatActivity", "Destroyed")
    }
}