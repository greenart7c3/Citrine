package com.greenart7c3.citrine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.greenart7c3.citrine.server.EventSubscription

@Dao
interface EventDao {
    @Query("SELECT * FROM EventEntity ORDER BY createdAt DESC")
    @Transaction
    fun getAll(): List<EventWithTags>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: EventEntity): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTags(tags: List<TagEntity>): List<Long>?

    @Query("SELECT * FROM EventEntity WHERE id = :id")
    @Transaction
    fun getById(id: String): EventWithTags?

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 3 ORDER BY createdAt DESC LIMIT 5")
    @Transaction
    fun getContactLists(pubkey: String): List<EventWithTags>

    @Query("SELECT id FROM EventEntity WHERE kind = :kind ORDER BY createdAt DESC")
    @Transaction
    fun getByKind(kind: Int): List<String>

    @Query("DELETE FROM EventEntity WHERE id in (:ids)")
    @Transaction
    fun delete(ids: List<String>)

    @Query("DELETE FROM TagEntity WHERE pkEvent = :id")
    @Transaction
    fun deletetags(id: String)

    @Query("DELETE FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind AND createdAt < (SELECT MAX(createdAt) FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind)")
    @Transaction
    fun deleteOldestByKind(kind: Int, pubkey: String)

    @Query(
        """
        SELECT EventEntity.id 
          FROM EventEntity EventEntity
         WHERE EventEntity.pubkey = :pubkey 
           AND EventEntity.kind = :kind
           AND EventEntity.createdAt < (SELECT MAX(EventEntity.createdAt) 
                              FROM EventEntity EventEntity 
                             INNER JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent  
                             WHERE EventEntity.pubkey = :pubkey 
                               AND EventEntity.kind = :kind
                               AND TagEntity.col0Name = 'd'
                               AND TagEntity.col1Value = :dTagValue
                           )
          and EventEntity.id in (SELECT EventEntity.id
                       FROM EventEntity EventEntity
                      INNER JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent  
                      WHERE EventEntity.pubkey = :pubkey 
                        AND EventEntity.kind = :kind
                        AND TagEntity.col0Name = 'd'
                        AND TagEntity.col1Value = :dTagValue
                    )                 
        """
    )
    @Transaction
    fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertEventWithTags(dbEvent: EventWithTags) {
        deletetags(dbEvent.event.id)
        insertEvent(dbEvent.event)?.let {
            dbEvent.tags.forEach {
                it.pkEvent = dbEvent.event.id
            }

            insertTags(dbEvent.tags)

            EventSubscription.executeAll(dbEvent)
        }
    }
}
