package com.sirvivar.blifi.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE deviceAddress = :address ORDER BY timestamp ASC")
    fun getMessagesForDevice(address: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getMessagesForDeviceId(deviceId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deviceAddress IN (:addresses) ORDER BY timestamp ASC")
    fun getMessagesForAddresses(addresses: List<String>): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE deviceAddress = :address")
    suspend fun deleteConversation(address: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY deviceAddress) ORDER BY timestamp DESC")
    fun getLastMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deviceAddress = :address AND isRead = 0 ORDER BY timestamp ASC")
    fun getUnreadMessagesForDevice(address: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isRead = 1 WHERE deviceAddress = :address")
    suspend fun markConversationAsRead(address: String)

    @Query("UPDATE messages SET isRead = 1 WHERE deviceId = :deviceId AND isSentByUser = 0")
    suspend fun markReceivedMessagesAsRead(deviceId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE deviceAddress = :address AND isSentByUser = 0")
    suspend fun markReceivedMessagesAsReadByAddress(address: String)

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :id")
    suspend fun updateMessageReadStatus(id: Long, isRead: Boolean)
}
