package com.sirvivar.blifi.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,    // Bluetooth MAC address
    val name: String?,                   // Device name (can be null if unknown)
    val lastSeenTimestamp: Long,         // Last time device was seen/connected
    val isOnline: Boolean = false        // Current online status
)
