package com.sirvivar.blifi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val senderAddress: String, val text: String)

object ChatEventBus {
    private val scope = CoroutineScope(Dispatchers.Default)

    // For broadcasting new messages
    private val _events = MutableSharedFlow<ChatMessage>()
    val events = _events.asSharedFlow()

    // For tracking the currently visible chat address
    private val _activeChatAddress = MutableStateFlow<String?>(null)
    val activeChatAddress = _activeChatAddress.asStateFlow()

    fun postEvent(event: ChatMessage) {
        scope.launch {
            _events.emit(event)
        }
    }

    fun setActiveChatAddress(address: String?) {
        _activeChatAddress.value = address
    }
}