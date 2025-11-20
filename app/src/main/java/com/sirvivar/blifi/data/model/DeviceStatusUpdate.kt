package com.sirvivar.blifi.data.model

data class DeviceStatusUpdate(
    val address: String,
    val isOnline: Boolean,
    val lastSeenTimestamp: Long
)
