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
import java.util.concurrent.Executors

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 9,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var database: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase = database ?: synchronized(this) {
            val executor = Executors.newCachedThreadPool()
            val transactionExecutor = Executors.newCachedThreadPool()

            val instance = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "citrine_database",
            )
//                .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
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
                .build()
            database = instance
            instance
        }
    }
}

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 9,
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
                // .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
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
