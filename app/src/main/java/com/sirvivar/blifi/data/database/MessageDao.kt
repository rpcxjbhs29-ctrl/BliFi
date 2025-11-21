package com.sirvivar.blifi.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE deviceAddress = :address ORDER BY timestamp ASC")
    fun getMessagesForDevice(address: String): Flow<List<MessageEntity>>

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
}
