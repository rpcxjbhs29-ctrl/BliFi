package com.sirvivar.blifi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

// Data class to represent a message
data class ChatMessage(val senderAddress: String, val text: String)

// Singleton object to act as the event bus
object ChatEventBus {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _events = MutableSharedFlow<ChatMessage>()
    val events = _events.asSharedFlow()

    fun postEvent(event: ChatMessage) {
        scope.launch {
            _events.emit(event)
        }
    }
}