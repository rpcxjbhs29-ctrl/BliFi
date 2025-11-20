package com.sirvivar.blifi.data.model

data class DeviceInfo(
    val address: String,
    val name: String?,
    val lastSeenTimestamp: Long,
    val isOnline: Boolean
)
