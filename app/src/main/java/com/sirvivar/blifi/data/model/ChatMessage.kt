package com.sirvivar.blifi.data.model

data class ChatMessage(
    val isSentByUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
