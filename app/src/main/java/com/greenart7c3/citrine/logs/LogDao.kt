package com.greenart7c3.citrine.logs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insertAll(logs: List<LogEntity>)

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<LogEntity>>

    @Query("DELETE FROM logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM logs")
    suspend fun deleteAll()
}
