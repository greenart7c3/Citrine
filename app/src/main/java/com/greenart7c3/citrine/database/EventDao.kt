package com.greenart7c3.citrine.database

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventSubscription
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    class CountResult {
        var kind: Int = 0
        var count: Int = 0
    }

    @RawQuery
    fun getEvents(query: SupportSQLiteQuery): List<EventWithTags>

    @Query("SELECT kind, COUNT(*) count FROM EventEntity GROUP BY kind ORDER BY kind ASC")
    @Transaction
    fun countByKind(): Flow<List<CountResult>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    suspend fun insertEvent(event: EventEntity): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    suspend fun insertTags(tags: List<TagEntity>): List<Long>?

    @Query("SELECT * FROM EventEntity WHERE id = :id")
    @Transaction
    suspend fun getById(id: String): EventWithTags?

    @Query("SELECT * FROM EventEntity WHERE id in (:ids)")
    @Transaction
    suspend fun getByIds(ids: List<String>): List<EventWithTags>

    @Transaction
    suspend fun deleteEventsWithExpirations(now: Long) {
        val ids = countEventsWithExpirations(now)
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} expired events")
            delete(ids)
        }
    }

    @Query("SELECT TagEntity.pkEvent FROM TagEntity TagEntity WHERE TagEntity.col0Name = 'expiration' AND CAST(TagEntity.col1Value as INTEGER) < :now")
    @Transaction
    suspend fun countEventsWithExpirations(now: Long): List<String>

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 3 ORDER BY createdAt DESC, id ASC")
    @Transaction
    suspend fun getContactLists(pubkey: String): List<EventWithTags>

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 3 ORDER BY createdAt DESC, id ASC LIMIT 1")
    @Transaction
    suspend fun getContactList(pubkey: String): EventWithTags?

    @Query("SELECT * FROM EventEntity WHERE pubkey = :pubkey and kind = 10002 ORDER BY createdAt DESC, id ASC LIMIT 1")
    @Transaction
    suspend fun getAdvertisedRelayList(pubkey: String): EventWithTags?

    @Query("SELECT id FROM EventEntity WHERE kind = :kind AND pubkey = :pubkey ORDER BY createdAt DESC, id ASC")
    @Transaction
    suspend fun getByKind(kind: Int, pubkey: String): List<String>

    @Query("SELECT id FROM EventEntity WHERE kind = :kind AND pubkey = :pubkey AND createdAt >= :createdAt")
    @Transaction
    suspend fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String>

    @Transaction
    suspend fun delete(ids: List<String>, pubkey: String) {
        ids.forEach {
            val deleted = deleteById(it, pubkey)
            if (deleted > 0) {
                deletetags(it)
            }
        }
    }

    @Query("DELETE FROM EventEntity WHERE id = :id and pubkey = :pubkey")
    suspend fun deleteById(id: String, pubkey: String): Int

    @Query("DELETE FROM EventEntity WHERE id in (:ids)")
    @Transaction
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM TagEntity WHERE pkEvent = :id")
    @Transaction
    suspend fun deletetags(id: String)

    @Query("SELECT id from EventEntity")
    @Transaction
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind AND createdAt < (SELECT MAX(createdAt) FROM EventEntity WHERE pubkey = :pubkey AND kind = :kind)")
    @Transaction
    suspend fun deleteOldestByKind(kind: Int, pubkey: String)

    @Query("DELETE FROM EventEntity WHERE kind = :kind")
    @Transaction
    suspend fun deleteByKind(kind: Int)

    @Query("SELECT id FROM EventEntity WHERE kind >= 20000 AND kind < 30000 AND createdAt < :oneMinuteAgo")
    @Transaction
    suspend fun getEphemeralEvents(oneMinuteAgo: Long): List<String>

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
    suspend fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String>

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
    suspend fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String>

    @Transaction
    suspend fun deleteAll() {
        deleteAllTags()
        innerDeleteAll()
    }

    @Query("DELETE FROM EventEntity")
    @Transaction
    suspend fun innerDeleteAll()

    @Query("DELETE FROM TagEntity")
    @Transaction
    suspend fun deleteAllTags()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    @Transaction
    suspend fun insertEventWithTags(
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
                EventSubscription.executeAll(dbEvent, connection)
            }
        }
    }

    @Transaction
    @Query(
        """
    SELECT * FROM EventEntity
    WHERE kind = :kind
      AND (
        :createdAt IS NULL OR
        createdAt < :createdAt OR
        (createdAt = :createdAt AND id > :id)
      )
    ORDER BY createdAt DESC, id ASC
    LIMIT :limit
""",
    )
    suspend fun getByKindKeyset(
        kind: Int,
        createdAt: Long?,
        id: String?,
        limit: Int,
    ): List<EventWithTags>

    @Query("DELETE FROM EventEntity WHERE createdAt <= :until")
    @Transaction
    suspend fun deleteAll(until: Long)

    @Query("DELETE FROM EventEntity WHERE createdAt <= :until AND pubkey NOT IN (:pubKeys)")
    @Transaction
    suspend fun deleteAll(until: Long, pubKeys: Array<String>)

    @Transaction
    suspend fun deleteEphemeralEvents(oneMinuteAgo: Long) {
        val ids = getEphemeralEvents(oneMinuteAgo)
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} ephemeral events")
            delete(ids)
        }
    }

    @Query(
        """
        SELECT EventEntity.id
          FROM EventEntity EventEntity
         INNER JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent
         WHERE TagEntity.col0Name = 'e'
           AND TagEntity.col1Value = :eTagValue
           AND EventEntity.kind = 5
        """,
    )
    @Transaction
    suspend fun getDeletedEvents(eTagValue: String): List<String>

    @Query(
        """
        SELECT EventEntity.createdAt
          FROM EventEntity EventEntity
         INNER JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent
         WHERE TagEntity.col0Name = 'a'
           AND TagEntity.col1Value = :aTagValue
           AND EventEntity.kind = 5
        """,
    )
    @Transaction
    suspend fun getDeletedEventsByATag(aTagValue: String): List<Long>

    // Optimized queries for ContentProvider
    // Using sentinel values: Long.MIN_VALUE/MAX_VALUE means "no filter"
    @Query(
        """
        SELECT * FROM EventEntity
        WHERE (:createdAtFrom = -9223372036854775808 OR createdAt >= :createdAtFrom)
          AND (:createdAtTo = 9223372036854775807 OR createdAt <= :createdAtTo)
        ORDER BY createdAt DESC, id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    @Transaction
    suspend fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>

    @Query(
        """
        SELECT * FROM EventEntity
        WHERE pubkey = :pubkey
          AND (:kind = -1 OR kind = :kind)
          AND (:createdAtFrom = -9223372036854775808 OR createdAt >= :createdAtFrom)
          AND (:createdAtTo = 9223372036854775807 OR createdAt <= :createdAtTo)
        ORDER BY createdAt DESC, id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    @Transaction
    suspend fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>

    @Query(
        """
        SELECT * FROM EventEntity
        WHERE kind = :kind
          AND (:pubkey = '' OR pubkey = :pubkey)
          AND (:createdAtFrom = -9223372036854775808 OR createdAt >= :createdAtFrom)
          AND (:createdAtTo = 9223372036854775807 OR createdAt <= :createdAtTo)
        ORDER BY createdAt DESC, id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    @Transaction
    suspend fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>
}

data class EventKey(
    val createdAt: Long,
    val id: String,
)

class EventPagingSource(
    private val dao: EventDao,
    private val kind: Int,
) : PagingSource<EventKey, EventWithTags>() {

    override suspend fun load(
        params: LoadParams<EventKey>,
    ): LoadResult<EventKey, EventWithTags> {
        val key = params.key

        val data = dao.getByKindKeyset(
            kind = kind,
            createdAt = key?.createdAt,
            id = key?.id,
            limit = params.loadSize,
        )

        val nextKey = data.lastOrNull()?.event?.let {
            EventKey(it.createdAt, it.id)
        }

        return LoadResult.Page(
            data = data,
            prevKey = null,
            nextKey = nextKey,
        )
    }

    /**
     * For keyset pagination, the safest behavior is to restart from the top.
     * Paging will invalidate and reload cleanly.
     */
    override fun getRefreshKey(
        state: PagingState<EventKey, EventWithTags>,
    ): EventKey? = null
}
