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
     * Get all messages for a specific device name (merging multiple addresses)
     */
    fun getMessagesForName(name: String): Flow<List<ChatMessage>> {
        return kotlinx.coroutines.flow.flow {
            val addresses = deviceDao.getAddressesForName(name)
            // If no addresses found (e.g. manual name entry?), fallback to empty or handle
            if (addresses.isEmpty()) {
                emit(emptyList())
            } else {
                messageDao.getMessagesForAddresses(addresses).collect { entities ->
                    val chatMessages = entities.map { entity ->
                        ChatMessage(
                            isSentByUser = entity.isSentByUser,
                            text = entity.text,
                            timestamp = entity.timestamp
                        )
                    }
                    emit(chatMessages)
                }
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
            
            // 1. Map to initial Conversation objects
            val allConversations = messages.map { message ->
                val device = deviceMap[message.deviceAddress]
                Conversation(
                    deviceAddress = message.deviceAddress,
                    deviceName = (device?.name ?: message.deviceAddress).trim().replace(Regex("[\\x00-\\x1F]"), ""),
                    lastMessage = message.text,
                    timestamp = message.timestamp,
                    isOnline = device?.isOnline ?: false
                )
            }

            // 2. Group by deviceName and pick the most recent one
            allConversations
                .groupBy { it.deviceName }
                .map { (_, convos) ->
                    // Return the conversation with the latest timestamp
                    // We keep the address of the latest conversation so we can try to connect to it
                    convos.maxByOrNull { it.timestamp }!!
                }
                .sortedByDescending { it.timestamp }
        }
    }
}
