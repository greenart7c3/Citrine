package com.greenart7c3.citrine.database

import android.util.Log
import androidx.paging.PagingSource
import androidx.sqlite.db.SimpleSQLiteQuery
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import kotlinx.coroutines.flow.Flow

/**
 * Room / SQLite implementation of [EventStore].
 *
 * Delegates all operations to [AppDatabase] / [EventDao] using the existing
 * [EventRepository.createQuery] dynamic SQL builder for filter queries.
 */
class RoomEventStore(val database: AppDatabase) : EventStore {

    private fun dao() = database.eventDao()

    // ── Dynamic filter queries ────────────────────────────────────────────────

    override fun getEvents(filter: EventFilter): List<EventWithTags> {
        val (sql, params) = EventRepository.createQuery(filter, false)
        return dao().getEvents(SimpleSQLiteQuery(sql, params.toTypedArray()))
    }

    override fun countEvents(filter: EventFilter): Int {
        val (sql, params) = EventRepository.createQuery(filter, true)
        return dao().count(SimpleSQLiteQuery(sql, params.toTypedArray()))
    }

    // ── Flow for UI ───────────────────────────────────────────────────────────

    override fun countByKind(): Flow<List<EventDao.CountResult>> = dao().countByKind()

    // ── Point lookups ─────────────────────────────────────────────────────────

    override suspend fun getById(id: String): EventWithTags? = dao().getById(id)

    override suspend fun getByIds(ids: List<String>): List<EventWithTags> = dao().getByIds(ids)

    override suspend fun getAllIds(): List<String> = dao().getAllIds()

    override suspend fun getByKind(kind: Int, pubkey: String): List<String> = dao().getByKind(kind, pubkey)

    override suspend fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String> = dao().getByKindNewest(kind, pubkey, createdAt)

    override suspend fun getContactList(pubkey: String): EventWithTags? = dao().getContactList(pubkey)

    override suspend fun getContactLists(pubkey: String): List<EventWithTags> = dao().getContactLists(pubkey)

    override suspend fun getAdvertisedRelayList(pubkey: String): EventWithTags? = dao().getAdvertisedRelayList(pubkey)

    override suspend fun getDeletedEvents(eTagValue: String): List<String> = dao().getDeletedEvents(eTagValue)

    override suspend fun getDeletedEventsByATag(aTagValue: String): List<Long> = dao().getDeletedEventsByATag(aTagValue)

    override suspend fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String> = dao().getOldestReplaceable(kind, pubkey, dTagValue)

    override suspend fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String> = dao().getNewestReplaceable(kind, pubkey, dTagValue, createdAt)

    override suspend fun getByKindKeyset(kind: Int, createdAt: Long?, id: String?, limit: Int): List<EventWithTags> = dao().getByKindKeyset(kind, createdAt, id, limit)

    // ── ContentProvider queries ───────────────────────────────────────────────

    override suspend fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> = dao().getEventsByDateRange(createdAtFrom, createdAtTo, limit, offset)

    override suspend fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> = dao().getEventsByPubkey(pubkey, kind, createdAtFrom, createdAtTo, limit, offset)

    override suspend fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> = dao().getEventsByKind(kind, pubkey, createdAtFrom, createdAtTo, limit, offset)

    // ── Writes ────────────────────────────────────────────────────────────────

    override suspend fun insertEventWithTags(
        dbEvent: EventWithTags,
        connection: Connection?,
        sendEventToSubscriptions: Boolean,
    ): Boolean {
        // insertEvent returns null when the row already existed (IGNORE conflict)
        dao().deletetags(dbEvent.event.id)
        val inserted = dao().insertEvent(dbEvent.event)
        if (inserted != null) {
            dbEvent.tags.forEach { it.pkEvent = dbEvent.event.id }
            dao().insertTags(dbEvent.tags)
            if (sendEventToSubscriptions && connection != null) {
                com.greenart7c3.citrine.server.EventSubscription.executeAll(dbEvent, connection)
            }
            return true
        }
        return false
    }

    // ── Deletes ───────────────────────────────────────────────────────────────

    override suspend fun deleteById(id: String, pubkey: String): Int = dao().deleteById(id, pubkey)

    override suspend fun delete(ids: List<String>) = dao().delete(ids)

    override suspend fun delete(ids: List<String>, pubkey: String) = dao().delete(ids, pubkey)

    override suspend fun deleteAll() = dao().deleteAll()

    override suspend fun deleteAll(until: Long) = dao().deleteAll(until)

    override suspend fun deleteAll(until: Long, pubKeys: Array<String>) = dao().deleteAll(until, pubKeys)

    override suspend fun deleteByKind(kind: Int) = dao().deleteByKind(kind)

    override suspend fun deleteOldestByKind(kind: Int, pubkey: String) = dao().deleteOldestByKind(kind, pubkey)

    override suspend fun deleteEphemeralEvents(oneMinuteAgo: Long) {
        val ids = dao().getEphemeralEvents(oneMinuteAgo)
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} ephemeral events")
            dao().delete(ids)
        }
    }

    override suspend fun deleteEventsWithExpirations(now: Long) {
        val ids = dao().countEventsWithExpirations(now)
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} expired events")
            dao().delete(ids)
        }
    }

    // ── Paging ────────────────────────────────────────────────────────────────

    override fun createPagingSource(kind: Int): PagingSource<EventKey, EventWithTags> = EventPagingSource(dao(), kind)
}
