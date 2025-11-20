package com.sirvivar.blifi.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeenTimestamp DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>
    
    @Query("SELECT * FROM devices WHERE address = :address")
    suspend fun getDevice(address: String): DeviceEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)
    
    @Query("UPDATE devices SET isOnline = :isOnline WHERE address = :address")
    suspend fun updateOnlineStatus(address: String, isOnline: Boolean)
    
    @Query("UPDATE devices SET lastSeenTimestamp = :timestamp WHERE address = :address")
    suspend fun updateLastSeen(address: String, timestamp: Long)
    
    @Query("UPDATE devices SET isOnline = :isOnline, lastSeenTimestamp = :timestamp WHERE address = :address")
    suspend fun updateDeviceStatus(address: String, isOnline: Boolean, timestamp: Long)
}
