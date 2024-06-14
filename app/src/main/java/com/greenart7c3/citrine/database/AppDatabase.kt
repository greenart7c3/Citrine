package com.greenart7c3.citrine.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.greenart7c3.citrine.BuildConfig
import java.util.concurrent.Executors

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var database: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return database ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    "citrine_database",
                )
                    .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                    .build()

                database = instance
                instance
            }
        }
    }
}

class AppDatabaseCallback : RoomDatabase.QueryCallback {
    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        if (BuildConfig.DEBUG) {
            Log.d("AppDatabase", "Query: $sqlQuery")
            bindArgs.forEach {
                Log.d("AppDatabase", "BindArg: $it")
            }
        }
    }
}
