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
    version = 4,
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
                // .setQueryCallback(AppDatabaseCallback(), Executors.newSingleThreadExecutor())
                .setQueryExecutor(executor)
                .setTransactionExecutor(transactionExecutor)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .build()

            instance.openHelper.writableDatabase.execSQL("VACUUM")
            database = instance
            instance
        }
    }
}

@Database(
    entities = [EventEntity::class, TagEntity::class],
    version = 4,
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
