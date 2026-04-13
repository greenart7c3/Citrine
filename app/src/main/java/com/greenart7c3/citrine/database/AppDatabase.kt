package com.greenart7c3.citrine.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Database(
    entities = [EventEntity::class, TagEntity::class, EventFTS::class],
    version = 11,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var database: AppDatabase? = null

        private val _isDatabaseUpgrading = MutableStateFlow(false)
        val isDatabaseUpgrading: StateFlow<Boolean> = _isDatabaseUpgrading

        private const val TARGET_VERSION = 11

        private fun checkNeedsMigration(context: Context): Boolean {
            val dbFile = context.getDatabasePath("citrine_database")
            if (!dbFile.exists()) return false
            return try {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.version > 0 && db.version < TARGET_VERSION
                }
            } catch (e: Exception) {
                Log.w(Citrine.TAG, "Could not check database version", e)
                false
            }
        }

        fun getDatabase(context: Context): AppDatabase = database ?: synchronized(this) {
            if (database != null) return database!!

            if (checkNeedsMigration(context)) {
                _isDatabaseUpgrading.value = true
            }

            val numCores = Runtime.getRuntime().availableProcessors()
            val executor = Executors.newFixedThreadPool(numCores * 2)
            val transactionExecutor = Executors.newFixedThreadPool(2)

            val instance = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "citrine_database",
            )
//                .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryExecutor(executor)
                .setTransactionExecutor(transactionExecutor)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .addMigrations(MIGRATION_10_11)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // mmap_size returns a result row, so use query() not execSQL()
                        db.execSQL("PRAGMA synchronous=NORMAL;")
                        db.execSQL("PRAGMA cache_size=-32000;")
                        db.execSQL("PRAGMA temp_store=MEMORY;")
                        db.query("PRAGMA mmap_size=268435456").close()
                        _isDatabaseUpgrading.value = false
                    }
                })
                .build()

            database = instance
            instance
        }
    }
}

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 11,
)
@TypeConverters(Converters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var database: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase = database ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "citrine_history_database",
            )
//                .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .addMigrations(MIGRATION_10_11)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA synchronous=NORMAL;")
                        db.execSQL("PRAGMA cache_size=-32000;")
                        db.execSQL("PRAGMA temp_store=MEMORY;")
                        db.query("PRAGMA mmap_size=268435456").close()
                    }
                })
                .build()

            database = instance
            instance
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `most_common_search_is_kind` ON `EventEntity` (`kind` ASC)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `TagEntity` ADD COLUMN `kind` INTEGER NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `TagEntity` SET `kind` = (SELECT e.kind from EventEntity e WHERE e.`id` = `pkEvent`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE `TagEntity` SET `kind` = (SELECT e.kind from EventEntity e WHERE e.`id` = `pkEvent`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `event_by_kind_created_at_id` ON `EventEntity` (`kind`, `createdAt`, `id`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT 'DROP INDEX ' || name || ';'\n" +
                "FROM sqlite_master\n" +
                "WHERE type = 'index'\n" +
                "  AND name NOT LIKE 'sqlite_%';",
        ).use {
            while (it.moveToNext()) {
                db.execSQL(it.getString(0))
            }
        }

        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_event_pubkey_created_id` ON `EventEntity` (`pubkey` ASC, `createdAt` DESC, `id` ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_event_kind_created_id` ON `EventEntity` (`kind` ASC, `createdAt` DESC, `id` ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `tags_by_pk_event` ON `TagEntity` (`pkEvent`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `tags_by_tags_on_person_or_events` ON `TagEntity` (`col0Name`, `col1Value`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_event_created_id` ON `EventEntity` (`createdAt` DESC, `id` ASC)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `tags_by_kind_tags_on_person_or_events` ON `TagEntity` (`kind`, `col0Name`, `col1Value`, `pkEvent`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_event_pubkey_kind_created_id` ON `EventEntity` (`pubkey` ASC, `kind` ASC, `createdAt` DESC, `id` ASC)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `event_fts` USING FTS4(content, content=`EventEntity`)")
        db.execSQL("INSERT INTO `event_fts` (`event_fts`) VALUES ('rebuild')")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `event_fts` ")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `event_fts` USING FTS4(content, content=`EventEntity`)")
        db.execSQL("INSERT INTO `event_fts` (`event_fts`) VALUES ('rebuild')")
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
