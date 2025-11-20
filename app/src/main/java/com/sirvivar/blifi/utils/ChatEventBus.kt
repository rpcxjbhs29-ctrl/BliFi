package com.sirvivar.blifi.utils

import com.sirvivar.blifi.data.model.ChatMessage
import com.sirvivar.blifi.data.model.ConnectionState
import com.sirvivar.blifi.data.model.DeviceStatusUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ChatEventBus {
    private val scope = CoroutineScope(Dispatchers.Default)

    // For broadcasting new messages
    private val _events = MutableSharedFlow<ChatMessage>()
    val events = _events.asSharedFlow()

    // For tracking the currently visible chat address
    private val _activeChatAddress = MutableStateFlow<String?>(null)
    val activeChatAddress = _activeChatAddress.asStateFlow()

    // For broadcasting the connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    
    // For broadcasting device status updates (online/offline)
    private val _deviceStatusUpdates = MutableSharedFlow<DeviceStatusUpdate>()
    val deviceStatusUpdates = _deviceStatusUpdates.asSharedFlow()

    fun postMessage(event: ChatMessage) {
        scope.launch {
            _events.emit(event)
        }
    }

    fun setActiveChatAddress(address: String?) {
        _activeChatAddress.value = address
    }

    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
    
    fun updateDeviceStatus(address: String, isOnline: Boolean, lastSeenTimestamp: Long = System.currentTimeMillis()) {
        scope.launch {
            _deviceStatusUpdates.emit(DeviceStatusUpdate(address, isOnline, lastSeenTimestamp))
        }
    }
}