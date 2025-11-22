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
            timestamp = timestamp,
            isRead = false  // Initially mark all messages as not read; will be updated when recipient reads them
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
                    timestamp = entity.timestamp,
                    isRead = entity.isRead
                )
            }
        }
    }

    fun getDeviceFlow(address: String): Flow<DeviceEntity?> {
        return deviceDao.getDeviceFlow(address)
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
                            timestamp = entity.timestamp,
                            isRead = entity.isRead
                        )
                    }
                    emit(chatMessages)
                }
            }
        }
    }
    
    /**
     * Get all messages for a specific device ID (merging multiple addresses)
     */
    fun getMessagesForDeviceId(deviceId: String): Flow<List<ChatMessage>> {
        return kotlinx.coroutines.flow.flow {
            val addresses = deviceDao.getAddressesForDeviceId(deviceId)
            if (addresses.isEmpty()) {
                emit(emptyList())
            } else {
                messageDao.getMessagesForAddresses(addresses).collect { entities ->
                    val chatMessages = entities.map { entity ->
                        ChatMessage(
                            isSentByUser = entity.isSentByUser,
                            text = entity.text,
                            timestamp = entity.timestamp,
                            isRead = entity.isRead
                        )
                    }.sortedBy { it.timestamp }
                    emit(chatMessages)
                }
            }
        }
    }
    
    /**
     * Get the device ID for a given address
     */
    suspend fun getDeviceIdForAddress(address: String): String? {
        return deviceDao.getDevice(address)?.deviceId?.takeIf { it.isNotEmpty() }
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
    suspend fun saveOrUpdateDevice(address: String, name: String?, isOnline: Boolean = false, deviceId: String = "") {
        val device = DeviceEntity(
            address = address,
            deviceId = deviceId,
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
     * Mark all messages in a conversation as read
     */
    suspend fun markConversationAsRead(address: String) {
        messageDao.markConversationAsRead(address)
    }

    /**
     * Update the read status of a specific message
     */
    suspend fun updateMessageReadStatus(id: Long, isRead: Boolean) {
        messageDao.updateMessageReadStatus(id, isRead)
    }

    /**
     * Mark received messages in a conversation as read by address
     */
    suspend fun markReceivedMessagesAsReadByAddress(address: String) {
        messageDao.markReceivedMessagesAsReadByAddress(address)
    }

    /**
     * Get all conversations with latest message and device info
     */
    fun getConversations(): Flow<List<Conversation>> {
        return combine(
            deviceDao.getAllDevices(),
            messageDao.getAllMessages()
        ) { devices, messages ->
            // Group devices by deviceId
            devices
                .filter { it.deviceId.isNotEmpty() } // Only process devices with valid IDs
                .groupBy { it.deviceId }
                .mapNotNull { (deviceId, deviceList) ->
                    // Use the most recent address for this deviceId
                    val primaryDevice = deviceList.maxByOrNull { it.lastSeenTimestamp } ?: return@mapNotNull null
                    
                    // Get all messages for all addresses associated with this deviceId
                    val deviceMessages = messages.filter { msg ->
                        deviceList.any { dev -> dev.address == msg.deviceAddress }
                    }
                    
                    val lastMessage = deviceMessages.maxByOrNull { it.timestamp }
                    
                    // Only include if there are messages
                    if (lastMessage != null) {
                        Conversation(
                            deviceId = deviceId,
                            deviceAddress = primaryDevice.address,
                            deviceName = (primaryDevice.name ?: primaryDevice.address).trim().replace(Regex("[\\x00-\\x1F]"), ""),
                            lastMessage = lastMessage.text,
                            lastMessageTime = lastMessage.timestamp,
                            isSentByUser = lastMessage.isSentByUser,
                            isOnline = primaryDevice.isOnline
                        )
                    } else {
                        null
                    }
                }
                .sortedByDescending { it.lastMessageTime }
        }
    }
}
