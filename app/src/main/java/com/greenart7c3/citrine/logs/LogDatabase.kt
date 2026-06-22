package com.greenart7c3.citrine.logs

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LogEntity::class],
    version = 1,
)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var database: LogDatabase? = null

        fun getDatabase(context: Context): LogDatabase = database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                LogDatabase::class.java,
                "citrine_logs_database",
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Logs are disposable; if the schema ever changes just start fresh.
                .fallbackToDestructiveMigration(true)
                .build()
                .also { database = it }
        }
    }
}
