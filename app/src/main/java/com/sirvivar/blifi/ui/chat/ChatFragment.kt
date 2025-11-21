package com.sirvivar.blifi.ui.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R
import com.sirvivar.blifi.data.database.ChatDatabase
import com.sirvivar.blifi.data.model.ChatMessage
import com.sirvivar.blifi.data.model.ConnectionState
import com.sirvivar.blifi.data.repository.ChatRepository
import com.sirvivar.blifi.service.BluetoothChatService
import com.sirvivar.blifi.ui.SharedViewModel
import com.sirvivar.blifi.utils.ChatEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    companion object {
        private const val TAG = "ChatFragment"
    }

    private lateinit var singleChatView: ConstraintLayout
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: android.widget.ImageButton
    private lateinit var conversationListRecyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var chatViewModel: ChatViewModel

    // Header views
    private lateinit var btnBack: android.widget.ImageButton
    private lateinit var textChatName: android.widget.TextView
    private lateinit var textChatStatus: android.widget.TextView

    private lateinit var messageAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var currentDeviceAddress: String? = null

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private var chatService: BluetoothChatService? = null
    private var isBound = false
    private var pendingConnectionAddress: String? = null  // Store pending connection
    private lateinit var chatRepository: ChatRepository

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothChatService.LocalBinder
            chatService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected successfully")
            
            // If there's a pending connection, attempt it now
            pendingConnectionAddress?.let { address ->
                Log.d(TAG, "Attempting pending connection to: $address")
                chatService?.connectToDevice(address)
                pendingConnectionAddress = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            chatService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart() - Binding to service")
        Intent(activity, BluetoothChatService::class.java).also { intent ->
            activity?.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            activity?.unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_chats, container, false)
        
        // Initialize repository and ViewModel
        val database = ChatDatabase.getDatabase(requireContext())
        chatRepository = ChatRepository(database)
        val factory = ChatViewModelFactory(chatRepository)
        chatViewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        
        singleChatView = root.findViewById(R.id.view_single_chat)
        messagesRecyclerView = root.findViewById(R.id.recycler_chat_messages)
        messageInput = root.findViewById(R.id.message_input)
        sendButton = root.findViewById(R.id.send_button)
        
        // Initialize header views
        btnBack = root.findViewById(R.id.btn_back)
        textChatName = root.findViewById(R.id.text_chat_name)
        textChatStatus = root.findViewById(R.id.text_chat_status)
        
        btnBack.setOnClickListener {
             // Handle back navigation manually
             if (singleChatView.isVisible) {
                 sharedViewModel.clearSelectedDevice()
                 // If it was opened from list (not sharedViewModel), we need to manually close it
                 if (singleChatView.isVisible) {
                     currentDeviceAddress = null
                     // chatService?.disconnectClient() // Keep connection alive
                     singleChatView.isVisible = false
                     conversationListRecyclerView.isVisible = true
                     activity?.title = "Chats"
                     ChatEventBus.setActiveChatAddress(null)
                 }
             }
        }
        
        conversationListRecyclerView = root.findViewById(R.id.recycler_chat_list)
        setupConversationList()
        setupSingleChatView()
        
        return root
    }

    private fun setupConversationList() {
        conversationAdapter = ConversationAdapter { conversation ->
            openChat(conversation.deviceAddress, conversation.deviceName)
        }
        conversationListRecyclerView.layoutManager = LinearLayoutManager(context)
        conversationListRecyclerView.adapter = conversationAdapter
        
        chatViewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            conversationAdapter.submitList(conversations)
            conversationListRecyclerView.isVisible = !singleChatView.isVisible
        }
    }

    private fun openChat(address: String, name: String) {
        currentDeviceAddress = address
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        
        singleChatView.isVisible = true
        conversationListRecyclerView.isVisible = false
        
        textChatName.text = name
        textChatStatus.text = "Offline" // Default
        
        // Get deviceId and load chat history
        viewLifecycleOwner.lifecycleScope.launch {
            val deviceId = chatRepository.getDeviceIdForAddress(address)
            
            if (deviceId != null) {
                // DeviceId is available, load chat history
                loadChatHistory(deviceId)
            } else {
                // Device not in DB yet, wait for identity exchange
                Log.d(TAG, "Device ID not available yet for $address, waiting for IAM exchange...")
                delay(1000)  // Wait for IAM message exchange
                val retryDeviceId = chatRepository.getDeviceIdForAddress(address)
                if (retryDeviceId != null) {
                    loadChatHistory(retryDeviceId)
                } else {
                    // Still no deviceId, messages will load once IAM is received
                    Log.d(TAG, "Still no device ID for $address after retry")
                }
            }
        }
        
        // Observe device status for this address
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getDeviceFlow(address).collect { device ->
                if (device != null) {
                    textChatName.text = device.name ?: "Unknown"
                    
                    // If deviceId becomes available and we haven't loaded history yet, load it now
                    if (device.deviceId.isNotEmpty() && messages.isEmpty()) {
                        loadChatHistory(device.deviceId)
                    }
                    
                    if (device.isOnline) {
                        textChatStatus.text = "Online"
                    } else {
                        val lastSeen = if (device.lastSeenTimestamp > 0) {
                            java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(device.lastSeenTimestamp))
                        } else {
                            "Never"
                        }
                        textChatStatus.text = "Last seen: $lastSeen"
                    }
                }
            }
        }
        ChatEventBus.setActiveChatAddress(address)
        
        // Try to connect if service is ready
        if (isBound && chatService != null) {
            Log.d(TAG, "Connecting to: $address")
            chatService?.connectToDevice(address)
        } else {
            Log.d(TAG, "Service not ready, storing pending connection: $address")
            pendingConnectionAddress = address
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel.selectedDevice.observe(viewLifecycleOwner) { scanResult ->
            if (scanResult != null) {
                // Save device to database first
                viewLifecycleOwner.lifecycleScope.launch {
                    chatRepository.saveOrUpdateDevice(
                        scanResult.device.address,
                        scanResult.device.name,
                        isOnline = true
                    )
                }
                openChat(scanResult.device.address, scanResult.device.name ?: scanResult.device.address)
            } else {
                // Only go back to list if we are not already in a chat initiated from list
                // But sharedViewModel.clearSelectedDevice() is called when we want to go back
                if (singleChatView.isVisible) {
                    currentDeviceAddress = null
                    // chatService?.disconnectClient() // Keep connection alive
                    singleChatView.isVisible = false
                    conversationListRecyclerView.isVisible = true
                    activity?.title = "Chats"
                    ChatEventBus.setActiveChatAddress(null)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Listen for new messages
                launch {
                    ChatEventBus.events.collect { chatMessage ->
                        // Messages from ChatEventBus are already saved to DB by the service
                        // The loadChatHistory flow will update the UI automatically
                        // We don't need to do anything here except maybe scroll
                        // But loadChatHistory already handles scrolling
                    }
                }
                // Listen for connection state changes
                var previousState: ConnectionState? = null
                launch {
                    ChatEventBus.connectionState.collect { state ->
                        when(state) {
                            ConnectionState.CONNECTED -> {
                                Toast.makeText(context, "Connected", Toast.LENGTH_SHORT).show()
                                previousState = ConnectionState.CONNECTED
                            }
                            ConnectionState.CONNECTING -> {
                                Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()
                                previousState = ConnectionState.CONNECTING
                            }
                            ConnectionState.DISCONNECTED -> {
                                // Only clear selection if we were previously connected/connecting
                                // Don't clear on initial fragment load when state is already DISCONNECTED
                                if (previousState == ConnectionState.CONNECTED || previousState == ConnectionState.CONNECTING) {
                                    if (sharedViewModel.selectedDevice.value != null) {
                                        Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                                        sharedViewModel.clearSelectedDevice()
                                    }
                                }
                                previousState = ConnectionState.DISCONNECTED
                            }
                        }
                    }
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (singleChatView.isVisible) {
                    sharedViewModel.clearSelectedDevice()
                    // If it was opened from list (not sharedViewModel), we need to manually close it
                    if (singleChatView.isVisible) {
                        currentDeviceAddress = null
                        // chatService?.disconnectClient() // Keep connection alive
                        singleChatView.isVisible = false
                        conversationListRecyclerView.isVisible = true
                        activity?.title = "Chats"
                        ChatEventBus.setActiveChatAddress(null)
                    }
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupSingleChatView() {
        // You'll need to update your ChatAdapter to handle ChatMessage objects
        messageAdapter = ChatAdapter(messages)
        messagesRecyclerView.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        messagesRecyclerView.adapter = messageAdapter

        sendButton.setOnClickListener {
            val messageText = messageInput.text.toString().trim()
            if (messageText.isNotEmpty()) {
                val success = chatService?.sendMessage(messageText) ?: false
                
                if (success) {
                    messageInput.text.clear()
                    
                    // Save sent message to database - UI will update from database flow
                    currentDeviceAddress?.let { address ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            chatRepository.saveMessage(
                                address,
                                messageText,
                                isSentByUser = true,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                } else {
                    Toast.makeText(context, "Failed to send message. Not connected.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadChatHistory(deviceId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.getMessagesForDeviceId(deviceId).collect { historyMessages ->
                // Always update from database (source of truth)
                // But only if we're still showing this chat
                if (currentDeviceAddress != null) {
                    messages.clear()
                    messages.addAll(historyMessages)
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty()) {
                        messagesRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }
}