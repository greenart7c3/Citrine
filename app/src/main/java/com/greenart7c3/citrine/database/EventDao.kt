package com.greenart7c3.citrine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EventDao {
    @Query("SELECT * FROM EventEntity ORDER BY createdAt DESC")
    @Transaction
    fun getAll(): List<EventWithTags>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: EventEntity): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTags(tags: List<TagEntity>): List<Long>?

    @Query("SELECT * FROM EventEntity WHERE pk = :pk")
    @Transaction
    fun getByPk(pk: String): EventWithTags

    @Query("SELECT * FROM EventEntity WHERE id = :id")
    @Transaction
    fun getById(id: String): EventWithTags?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertEventWithTags(dbEvent: EventEntity, dbTags: List<TagEntity>) {
        insertEvent(dbEvent)?.let { eventPK ->
            if (eventPK >= 0) {
                dbTags.forEach {
                    it.pkEvent = eventPK
                }

                insertTags(dbTags)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertEventWithTags(dbEvent: EventWithTags) {
        insertEvent(dbEvent.event)?.let { eventPK ->
            if (eventPK >= 0) {
                dbEvent.tags.forEach {
                    it.pkEvent = eventPK
                }

                insertTags(dbEvent.tags)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertListOfEventWithTags(dbEvent: List<EventWithTags>) {
        dbEvent.forEach {
            insertEvent(it.event)?.let { eventPK ->
                if (eventPK >= 0) {
                    it.tags.forEach {
                        it.pkEvent = eventPK
                    }

                    insertTags(it.tags)
                }
            }
        }
    }
}