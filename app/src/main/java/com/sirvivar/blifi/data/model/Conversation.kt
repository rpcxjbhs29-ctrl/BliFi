package com.sirvivar.blifi.data.model

data class Conversation(
    val deviceId: String,        // Permanent device UUID for grouping
    val deviceAddress: String,   // Current/last known MAC address
    val deviceName: String,
    val lastMessage: String?,
    val lastMessageTime: Long,
    val isSentByUser: Boolean,
    val isOnline: Boolean = false
)
