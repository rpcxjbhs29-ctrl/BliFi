package com.sirvivar.blifi.utils

import android.content.Context
import android.provider.Settings
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
    
    /**
     * Get a permanent device UUID that persists across app reinstalls and data clears.
     * Uses Android ID combined with package name for app-specific identification.
     * Returns shortened 8-character UUID to fit within BLE 20-byte message limit.
     */
    fun getDeviceUUID(context: Context): String {
        // Use Android ID as the permanent device identifier
        // This persists across app reinstalls and data clears (on Android 8.0+)
        // It's unique per app signing key and device
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        
        // Combine with package name to create app-specific UUID
        // This ensures different apps have different IDs even on the same device
        val uniqueString = "${context.packageName}-$androidId"
        
        // Create a deterministic UUID from the unique string
        val fullUUID = UUID.nameUUIDFromBytes(uniqueString.toByteArray()).toString()
        
        // Return first 8 characters to fit in BLE message (IAM:xxxxxxxx:Username = ~20 bytes)
        return fullUUID.substring(0, 8)
    }
}
