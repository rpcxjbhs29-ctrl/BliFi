package com.sirvivar.blifi.data.model

data class Conversation(
    val deviceAddress: String,
    val deviceName: String,
    val lastMessage: String,
    val timestamp: Long,
    val isOnline: Boolean
)
