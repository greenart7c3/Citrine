package com.greenart7c3.citrine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.greenart7c3.citrine.server.Connection
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    class CountResult {
        var kind: Int = 0
        var count: Int = 0
    }

    @RawQuery
    @Transaction
    fun getEvents(query: SupportSQLiteQuery): List<EventWithTags>

    @Query("SELECT kind, COUNT(*) count FROM EventEntity GROUP BY kind ORDER BY kind ASC")
    @Transaction
    fun countByKind(): Flow<List<CountResult>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertEvent(event: EventEntity): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertTags(tags: List<TagEntity>): List<Long>?

    @Query("SELECT * FROM EventEntity WHERE id = :id")
    @Transaction
    fun getById(id: String): EventWithTags?

    @Transaction
    fun deleteEventsWithExpirations(now: Long) {
        val ids = countEventsWithExpirations(now)
        if (ids.isNotEmpty()) {
            delete(ids)
        }
    }

    @Query("SELECT TagEntity.pkEvent FROM TagEntity TagEntity WHERE TagEntity.col0Name = 'expiration' AND CAST(TagEntity.col1Value as INTEGER) < :now")
    @Transaction
    fun countEventsWithExpirations(now: Long): List<String>

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 3 ORDER BY createdAt DESC, id ASC LIMIT 5")
    @Transaction
    fun getContactLists(pubkey: String): List<EventWithTags>

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 3 ORDER BY createdAt DESC, id ASC LIMIT 1")
    @Transaction
    fun getContactList(pubkey: String): EventWithTags?

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 10002 ORDER BY createdAt DESC, id ASC LIMIT 1")
    @Transaction
    fun getAdvertisedRelayList(pubkey: String): EventWithTags?

    @Query("SELECT id FROM EventEntity WHERE kind = :kind AND pubkey = :pubkey ORDER BY createdAt DESC, id ASC")
    @Transaction
    fun getByKind(kind: Int, pubkey: String): List<String>

    @Query("SELECT id FROM EventEntity WHERE kind = :kind AND pubkey = :pubkey AND createdAt >= :createdAt")
    @Transaction
    fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String>

    @Query("DELETE FROM EventEntity WHERE id in (:ids) and pubkey = :pubkey")
    @Transaction
    fun delete(ids: List<String>, pubkey: String)

    @Query("DELETE FROM EventEntity WHERE id in (:ids)")
    @Transaction
    fun delete(ids: List<String>)

    @Query("DELETE FROM TagEntity WHERE pkEvent = :id")
    @Transaction
    fun deletetags(id: String)

    @Query("SELECT id from EventEntity")
    @Transaction
    fun getAllIds(): List<String>

    @Query("DELETE FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind AND createdAt < (SELECT MAX(createdAt) FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind)")
    @Transaction
    fun deleteOldestByKind(kind: Int, pubkey: String)

    @Query("SELECT id FROM EventEntity WHERE kind >= 20000 AND kind < 30000")
    @Transaction
    fun getEphemeralEvents(): List<String>

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
        """,
    )
    @Transaction
    fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String>

    @Query(
        """
        SELECT EventEntity.id
          FROM EventEntity EventEntity
         WHERE EventEntity.pubkey = :pubkey
           AND EventEntity.kind = :kind
           AND EventEntity.createdAt >= :createdAt
          and EventEntity.id in (SELECT EventEntity.id
                       FROM EventEntity EventEntity
                      INNER JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent
                      WHERE EventEntity.pubkey = :pubkey
                        AND EventEntity.kind = :kind
                        AND TagEntity.col0Name = 'd'
                        AND TagEntity.col1Value = :dTagValue
                    )
        """,
    )
    @Transaction
    fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String>

    @Query("DELETE FROM EventEntity")
    @Transaction
    fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    fun insertEventWithTags(
        dbEvent: EventWithTags,
        connection: Connection?,
        sendEventToSubscriptions: Boolean = true,
    ) {
        deletetags(dbEvent.event.id)
        insertEvent(dbEvent.event)?.let {
            dbEvent.tags.forEach {
                it.pkEvent = dbEvent.event.id
            }

            insertTags(dbEvent.tags)

            if (sendEventToSubscriptions && connection != null) {
                // EventSubscription.executeAll(dbEvent, connection)
            }
        }
    }

    @Query("SELECT * FROM EventEntity WHERE kind = :kind ORDER BY createdAt DESC, id ASC")
    @Transaction
    fun getByKind(kind: Int): Flow<List<EventWithTags>>

    @Query("DELETE FROM EventEntity WHERE createdAt <= :until")
    @Transaction
    fun deleteAll(until: Long)

    @Query("DELETE FROM EventEntity WHERE createdAt <= :until and pubkey NOT IN (:pubKeys)")
    @Transaction
    fun deleteAll(until: Long, pubKeys: String)

    @Transaction
    fun deleteEphemeralEvents() {
        val ids = getEphemeralEvents()
        if (ids.isNotEmpty()) {
            delete(ids)
        }
    }
}
