package com.greenart7c3.citrine.database

import androidx.paging.PagingSource
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventFilter
import kotlinx.coroutines.flow.Flow

/**
 * NostrDB implementation of [EventStore].
 *
 * NostrDB is a high-performance C library for storing and querying Nostr events, developed by
 * Damus. The Android bindings are available via the `nostrdb-android` library.
 *
 * To enable this backend, add the following to your `build.gradle.kts`:
 * ```
 * // In repositories (settings.gradle.kts):
 * maven { url = uri("https://jitpack.io") }
 *
 * // In dependencies (app/build.gradle.kts):
 * implementation("com.github.damus-io:nostrdb-android:<version>")
 * ```
 *
 * Then replace the TODO stubs below with actual nostrdb-android API calls.
 * See https://github.com/damus-io/nostrdb-android for documentation.
 *
 * Note: NostrDB stores events in a memory-mapped LMDB file and uses a native
 * NoteKey cursor API for queries. The database file is typically placed at
 * `context.filesDir/nostrdb`.
 */
class NostrDbEventStore : EventStore {

    // TODO: Initialize nostrdb-android:
    //   private val ndb: Ndb = Ndb(path = context.filesDir.absolutePath + "/nostrdb")

    override fun getEvents(filter: EventFilter): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented. See class documentation for setup instructions.")
    }

    override fun countEvents(filter: EventFilter): Int {
        TODO("NostrDB backend not yet implemented.")
    }

    override fun countByKind(): Flow<List<EventDao.CountResult>> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getById(id: String): EventWithTags? {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getByIds(ids: List<String>): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getAllIds(): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getByKind(kind: Int, pubkey: String): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getContactList(pubkey: String): EventWithTags? {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getContactLists(pubkey: String): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getAdvertisedRelayList(pubkey: String): EventWithTags? {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getDeletedEvents(eTagValue: String): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getDeletedEventsByATag(aTagValue: String): List<Long> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getByKindKeyset(kind: Int, createdAt: Long?, id: String?, limit: Int): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun insertEventWithTags(
        dbEvent: EventWithTags,
        connection: Connection?,
        sendEventToSubscriptions: Boolean,
    ): Boolean {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteById(id: String, pubkey: String): Int {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun delete(ids: List<String>) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun delete(ids: List<String>, pubkey: String) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteAll() {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteAll(until: Long) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteAll(until: Long, pubKeys: Array<String>) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteByKind(kind: Int) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteOldestByKind(kind: Int, pubkey: String) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteEphemeralEvents(oneMinuteAgo: Long) {
        TODO("NostrDB backend not yet implemented.")
    }

    override suspend fun deleteEventsWithExpirations(now: Long) {
        TODO("NostrDB backend not yet implemented.")
    }

    override fun createPagingSource(kind: Int): PagingSource<EventKey, EventWithTags> {
        TODO("NostrDB backend not yet implemented.")
    }
}
