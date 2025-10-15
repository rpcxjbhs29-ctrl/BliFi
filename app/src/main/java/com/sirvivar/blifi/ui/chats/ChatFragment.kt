package com.sirvivar.blifi.ui.chats

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.BluetoothChatService
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.R
import com.sirvivar.blifi.ui.ChatEventBus
import com.sirvivar.blifi.ui.SharedViewModel
import com.sirvivar.blifi.ui.chat.ChatAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment() {

    private lateinit var chatListView: RecyclerView
    private lateinit var singleChatView: ConstraintLayout
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button

    private lateinit var chatsListAdapter: ChatsAdapter
    private lateinit var messageAdapter: ChatAdapter

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var isReadyToSend = false

    private val messages = mutableListOf<Pair<Boolean, String>>()
    private var currentChatAddress: String? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            activity?.runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("ChatFragment", "GATT connected to ${gatt.device.address}")
                    Toast.makeText(context, "Connected", Toast.LENGTH_SHORT).show()
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isReadyToSend = false
                    Log.d("ChatFragment", "GATT disconnected")
                    Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                    sharedViewModel.clearSelectedDevice()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt.getService(MainActivity.SERVICE_UUID)?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                characteristic?.let {
                    gatt.setCharacteristicNotification(it, true)
                    val descriptor = it.getDescriptor(BluetoothChatService.CCCD_UUID)
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
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val messageBytes = characteristic.value
            val messageString = String(messageBytes, Charsets.UTF_8)
            Log.d("ChatFragment", "Client received notification: '$messageString'")

            lifecycleScope.launch {
                withContext(Dispatchers.Main) {
                    updateChatUI(messageString)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                isReadyToSend = true
                Log.d("ChatFragment", "Connection ready to send messages.")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("ChatFragment", "Message sent successfully")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_chats, container, false)
        chatListView = root.findViewById(R.id.recycler_chat_list)
        singleChatView = root.findViewById(R.id.view_single_chat)
        messagesRecyclerView = root.findViewById(R.id.recycler_chat_messages)
        messageInput = root.findViewById(R.id.message_input)
        sendButton = root.findViewById(R.id.send_button)
        setupChatList()
        setupSingleChatView()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel.selectedDevice.observe(viewLifecycleOwner) { scanResult ->
            if (scanResult != null) {
                messages.clear()
                messageAdapter.notifyDataSetChanged()
                currentChatAddress = scanResult.device.address
                connectToDevice(scanResult.device.address)
                chatListView.isVisible = false
                singleChatView.isVisible = true
                activity?.title = scanResult.device.name ?: scanResult.device.address

                // Tell the app this chat is now active
                ChatEventBus.setActiveChatAddress(scanResult.device.address)
            } else {
                disconnectGatt()
                chatListView.isVisible = true
                singleChatView.isVisible = false
                activity?.title = "Chats"

                // Tell the app no chat is active
                ChatEventBus.setActiveChatAddress(null)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatEventBus.events.collect { chatMessage ->
                    if (currentChatAddress != null) {
                        withContext(Dispatchers.Main) {
                            Log.d("ChatFragment", "EventBus message received and being displayed: '${chatMessage.text}'")
                            updateChatUI(chatMessage.text)
                        }
                    }
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (singleChatView.isVisible) {
                    sharedViewModel.clearSelectedDevice()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Safeguard to ensure the active chat is cleared when the view is destroyed
        ChatEventBus.setActiveChatAddress(null)
    }

    private fun updateChatUI(text: String) {
        messages.add(Pair(false, text))
        messageAdapter.notifyItemInserted(messages.size - 1)
        messagesRecyclerView.scrollToPosition(messages.size - 1)
    }

    private fun setupChatList() {
        chatsListAdapter = ChatsAdapter(emptyList()) { }
        chatListView.layoutManager = LinearLayoutManager(context)
        chatListView.adapter = chatsListAdapter
    }

    private fun setupSingleChatView() {
        messageAdapter = ChatAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        messagesRecyclerView.adapter = messageAdapter

        sendButton.setOnClickListener {
            val message = messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                if (!isReadyToSend) {
                    Toast.makeText(context, "Connection not ready", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val characteristic = bluetoothGatt
                    ?.getService(MainActivity.SERVICE_UUID)
                    ?.getCharacteristic(BluetoothChatService.CHAT_CHARACTERISTIC_UUID)
                characteristic?.let {
                    it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    it.value = message.toByteArray(Charsets.UTF_8)
                    if (bluetoothGatt?.writeCharacteristic(it) == true) {
                        messages.add(Pair(true, message))
                        messageAdapter.notifyItemInserted(messages.size - 1)
                        messagesRecyclerView.scrollToPosition(messages.size - 1)
                        messageInput.text.clear()
                    }
                }
            }
        }
    }

    private fun connectToDevice(address: String) {
        val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        device?.let {
            bluetoothGatt = it.connectGatt(context, false, gattCallback)
        }
    }

    private fun disconnectGatt() {
        isReadyToSend = false
        currentChatAddress = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onStop() {
        super.onStop()
        disconnectGatt()
    }
}