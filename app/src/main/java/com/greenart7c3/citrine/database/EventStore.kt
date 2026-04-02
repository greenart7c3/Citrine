package com.greenart7c3.citrine.database

import androidx.paging.PagingSource
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventFilter
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the Nostr event storage backend.
 *
 * All database backends (Room, NostrDB, rust-nostr LMDB, etc.) implement this interface,
 * allowing the rest of the application to be backend-agnostic.
 */
interface EventStore {

    // ── Dynamic filter queries (blocking, must be called on an IO thread) ──────

    /** Query events matching [filter]. Returns events ordered by createdAt DESC. */
    fun getEvents(filter: EventFilter): List<EventWithTags>

    /** Count events matching [filter]. */
    fun countEvents(filter: EventFilter): Int

    // ── Flow for UI ───────────────────────────────────────────────────────────

    /** Reactive count grouped by kind, for the Home screen stats table. */
    fun countByKind(): Flow<List<EventDao.CountResult>>

    // ── Point lookups ─────────────────────────────────────────────────────────

    suspend fun getById(id: String): EventWithTags?
    suspend fun getByIds(ids: List<String>): List<EventWithTags>
    suspend fun getAllIds(): List<String>
    suspend fun getByKind(kind: Int, pubkey: String): List<String>
    suspend fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String>
    suspend fun getContactList(pubkey: String): EventWithTags?
    suspend fun getContactLists(pubkey: String): List<EventWithTags>
    suspend fun getAdvertisedRelayList(pubkey: String): EventWithTags?
    suspend fun getDeletedEvents(eTagValue: String): List<String>
    suspend fun getDeletedEventsByATag(aTagValue: String): List<Long>
    suspend fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String>
    suspend fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String>
    suspend fun getByKindKeyset(kind: Int, createdAt: Long?, id: String?, limit: Int): List<EventWithTags>

    // ── ContentProvider queries ───────────────────────────────────────────────

    suspend fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>

    suspend fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>

    suspend fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags>

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Insert an event and its tags. Optionally notifies active subscriptions.
     * Returns true if the event was inserted (false = already existed / ignored).
     */
    suspend fun insertEventWithTags(
        dbEvent: EventWithTags,
        connection: Connection?,
        sendEventToSubscriptions: Boolean = true,
    ): Boolean

    // ── Deletes ───────────────────────────────────────────────────────────────

    suspend fun deleteById(id: String, pubkey: String): Int
    suspend fun delete(ids: List<String>)
    suspend fun delete(ids: List<String>, pubkey: String)
    suspend fun deleteAll()
    suspend fun deleteAll(until: Long)
    suspend fun deleteAll(until: Long, pubKeys: Array<String>)
    suspend fun deleteByKind(kind: Int)
    suspend fun deleteOldestByKind(kind: Int, pubkey: String)
    suspend fun deleteEphemeralEvents(oneMinuteAgo: Long)
    suspend fun deleteEventsWithExpirations(now: Long)

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Returns a [PagingSource] for paginating events of a given kind (Feed screen). */
    fun createPagingSource(kind: Int): PagingSource<EventKey, EventWithTags>
}
