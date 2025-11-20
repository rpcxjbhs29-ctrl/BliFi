package com.sirvivar.blifi.utils

import java.util.UUID

object Constants {
    val SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000FEE1-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PREFS_NAME = "BliFiPrefs"
    const val LAST_DEVICE_ADDRESS = "LastDeviceAddress"
    const val PREF_DEVICE_NAME = "device_name"
    const val DEFAULT_DEVICE_NAME = "BliFi User"
    
    const val FOREGROUND_CHANNEL_ID = "bli_fi_chat_channel"
    const val MESSAGE_CHANNEL_ID = "bli_fi_message_channel"
    const val FOREGROUND_NOTIFICATION_ID = 2
    const val MESSAGE_NOTIFICATION_ID = 3
    
    const val DATABASE_NAME = "blifi_chat_db"
}
