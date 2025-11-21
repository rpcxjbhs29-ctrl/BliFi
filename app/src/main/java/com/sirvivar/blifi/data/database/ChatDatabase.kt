package com.sirvivar.blifi.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sirvivar.blifi.utils.Constants.DATABASE_NAME

@Database(
    entities = [MessageEntity::class, DeviceEntity::class],
    version = 3,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun deviceDao(): DeviceDao
    
    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null
        
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add deviceId to devices table (non-null with default empty string)
                database.execSQL("ALTER TABLE devices ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
                
                // Add deviceId to messages table (nullable for backwards compatibility)
                database.execSQL("ALTER TABLE messages ADD COLUMN deviceId TEXT")
                
                // For existing devices, use MAC address as initial deviceId
                // This will be updated when devices reconnect and exchange proper UUIDs
                database.execSQL("UPDATE devices SET deviceId = address WHERE deviceId = ''")
            }
        }
        
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add profileEmoji to devices table (nullable for custom emoji avatars)
                database.execSQL("ALTER TABLE devices ADD COLUMN profileEmoji TEXT")
            }
        }
        
        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
