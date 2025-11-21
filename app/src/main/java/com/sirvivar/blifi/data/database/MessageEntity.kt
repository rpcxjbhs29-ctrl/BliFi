package com.sirvivar.blifi.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,      // Which device this message is with (MAC address)
    val deviceId: String? = null,   // Device UUID (for grouping, nullable for migration)
    val text: String,                // Message content
    val isSentByUser: Boolean,       // true if sent, false if received
    val timestamp: Long,             // Unix timestamp in milliseconds
    val isRead: Boolean = false      // true if message has been read, false if unread
)
