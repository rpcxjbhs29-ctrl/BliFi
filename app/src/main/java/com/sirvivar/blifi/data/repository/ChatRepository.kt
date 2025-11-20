package com.sirvivar.blifi.data.repository

import com.sirvivar.blifi.data.database.ChatDatabase
import com.sirvivar.blifi.data.database.DeviceEntity
import com.sirvivar.blifi.data.database.MessageEntity
import com.sirvivar.blifi.data.model.ChatMessage
import com.sirvivar.blifi.data.model.DeviceInfo
import com.sirvivar.blifi.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ChatRepository(private val database: ChatDatabase) {
    
    private val messageDao = database.messageDao()
    private val deviceDao = database.deviceDao()
    
    /**
     * Save a chat message to the database
     */
    suspend fun saveMessage(deviceAddress: String, text: String, isSentByUser: Boolean, timestamp: Long = System.currentTimeMillis()) {
        val message = MessageEntity(
            deviceAddress = deviceAddress,
            text = text,
            isSentByUser = isSentByUser,
            timestamp = timestamp
        )
        messageDao.insertMessage(message)
    }
    
    /**
     * Get all messages for a specific device as a Flow
     */
    fun getMessagesForDevice(deviceAddress: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesForDevice(deviceAddress).map { entities ->
            entities.map { entity ->
                ChatMessage(
                    isSentByUser = entity.isSentByUser,
                    text = entity.text,
                    timestamp = entity.timestamp
                )
            }
        }
    }
    
    /**
     * Update a device's online status
     */
    suspend fun updateDeviceOnlineStatus(address: String, isOnline: Boolean) {
        val timestamp = System.currentTimeMillis()
        deviceDao.updateDeviceStatus(address, isOnline, timestamp)
    }
    
    /**
     * Update the last seen timestamp for a device
     */
    suspend fun updateDeviceLastSeen(address: String, timestamp: Long = System.currentTimeMillis()) {
        deviceDao.updateLastSeen(address, timestamp)
    }
    
    /**
     * Save or update device information
     */
    suspend fun saveOrUpdateDevice(address: String, name: String?, isOnline: Boolean = false) {
        val device = DeviceEntity(
            address = address,
            name = name,
            lastSeenTimestamp = System.currentTimeMillis(),
            isOnline = isOnline
        )
        deviceDao.upsertDevice(device)
    }
    
    /**
     * Get all devices with their information
     */
    fun getAllDevices(): Flow<List<DeviceInfo>> {
        return deviceDao.getAllDevices().map { entities ->
            entities.map { entity ->
                DeviceInfo(
                    address = entity.address,
                    name = entity.name,
                    lastSeenTimestamp = entity.lastSeenTimestamp,
                    isOnline = entity.isOnline
                )
            }
        }
    }
    
    /**
     * Get a specific device
     */
    suspend fun getDevice(address: String): DeviceInfo? {
        val entity = deviceDao.getDevice(address)
        return entity?.let {
            DeviceInfo(
                address = it.address,
                name = it.name,
                lastSeenTimestamp = it.lastSeenTimestamp,
                isOnline = it.isOnline
            )
        }
    }
    
    /**
     * Delete all messages for a specific conversation
     */
    suspend fun deleteConversation(address: String) {
        messageDao.deleteConversation(address)
    }

    /**
     * Get all conversations with latest message and device info
     */
    fun getConversations(): Flow<List<Conversation>> {
        return combine(
            messageDao.getLastMessages(),
            deviceDao.getAllDevices()
        ) { messages, devices ->
            val deviceMap = devices.associateBy { it.address }
            messages.map { message ->
                val device = deviceMap[message.deviceAddress]
                Conversation(
                    deviceAddress = message.deviceAddress,
                    deviceName = device?.name ?: message.deviceAddress,
                    lastMessage = message.text,
                    timestamp = message.timestamp,
                    isOnline = device?.isOnline ?: false
                )
            }
        }
    }
}
