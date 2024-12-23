package com.greenart7c3.citrine.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import rust.nostr.sdk.NostrDatabase

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var database: AppDatabase? = null
        private var nostrdb: NostrDatabase? = null
        private var nostrEphemeraldb: NostrDatabase? = null

        fun getNostrDatabase(): NostrDatabase {
            return nostrdb ?: synchronized(this) {
                val instance = NostrDatabase.lmdb(
                    Citrine.getInstance().filesDir.path + "/citrine_nostrdb",
                )

                nostrdb = instance
                instance
            }
        }

        fun getNostrEphemeralDatabase(): NostrDatabase {
            return nostrEphemeraldb ?: synchronized(this) {
                val instance = NostrDatabase.lmdb(
                    Citrine.getInstance().filesDir.path + "/citrine_ephemeral_nostrdb",
                )
                nostrEphemeraldb = instance
                instance
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return database ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    "citrine_database",
                )
                    // .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                    .addMigrations(MIGRATION_1_2)
                    .build()

                database = instance
                instance
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `most_common_search_is_kind` ON `EventEntity` (`kind` ASC)")
    }
}

class AppDatabaseCallback : RoomDatabase.QueryCallback {
    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        if (BuildConfig.DEBUG) {
            Log.d(Citrine.TAG, "Query: $sqlQuery")
            bindArgs.forEach {
                Log.d(Citrine.TAG, "BindArg: $it")
            }
        }
    }
}
