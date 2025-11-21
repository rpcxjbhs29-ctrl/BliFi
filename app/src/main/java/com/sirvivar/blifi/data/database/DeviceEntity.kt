package com.sirvivar.blifi.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,    // Bluetooth MAC address
    val deviceId: String = "",           // Permanent device UUID (for grouping chats)
    val name: String?,                   // Device name (user-changeable, for display)
    val lastSeenTimestamp: Long,         // Last time device was seen/connected
    val isOnline: Boolean = false,       // Current online status
    val profileEmoji: String? = null     // Custom emoji for profile (null = show initial)
)
