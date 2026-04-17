package com.greenart7c3.citrine.storage

import android.content.Context
import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.storage.lmdb.LmdbCodec
import com.greenart7c3.citrine.storage.lmdb.LmdbDbi
import com.greenart7c3.citrine.storage.lmdb.LmdbEnv
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.io.File
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CountByKindResult(val kind: Int, val count: Int)

/**
 * LMDB-backed event store. Replaces the old Room database. All public methods
 * operate on Quartz [Event]s; callers never touch storage entities directly.
 *
 * Layout on disk (under `ctx.filesDir/citrine_lmdb/`):
 *   - events.mdb     — snapshot of the main DB (key = event id, value = JSON)
 *   - events.log     — append journal for the main DB
 *   - lock.mdb       — environment lock file
 *
 * Secondary indexes are kept in memory and rebuilt from the main DB on open.
 * Dataset sizes on a mobile relay fit comfortably in heap, and keeping the
 * on-disk format tiny (one snapshot + append journal per Dbi) is easier to
 * reason about than a native LMDB binding.
 */
class EventStore private constructor(
    private val context: Context,
    private val dirName: String,
    private val runLegacyMigration: Boolean,
) {
    private val envDir: File = File(context.filesDir, dirName)
    private val env: LmdbEnv = LmdbEnv.open(envDir)
    private val eventsDbi: LmdbDbi<String, Event> = env.openDbi("events", EventCodec)

    // Secondary indexes, all guarded by [indexLock].
    private val indexLock = ReentrantReadWriteLock(true)

    private val byKind = HashMap<Int, TreeSet<EventKey>>()
    private val byPubkey = HashMap<String, TreeSet<EventKey>>()
    private val byPubkeyKind = HashMap<Pair<String, Int>, TreeSet<EventKey>>()
    private val byTag = HashMap<Pair<String, String>, MutableSet<String>>()
    private val allKeys = TreeSet<EventKey>()

    private val countByKindState = MutableStateFlow<List<CountByKindResult>>(emptyList())

    private val migrationState = MutableStateFlow(false)
    val isMigrating: StateFlow<Boolean> = migrationState.asStateFlow()

    init {
        rebuildIndexes()
        if (runLegacyMigration) migrateFromRoomIfNeeded()
    }

    private fun rebuildIndexes() {
        indexLock.writeLock().lock()
        try {
            byKind.clear()
            byPubkey.clear()
            byPubkeyKind.clear()
            byTag.clear()
            allKeys.clear()
            env.readTxn { txn ->
                for ((_, event) in eventsDbi.iterateAscending(txn)) {
                    indexEvent(event)
                }
            }
            emitCountByKind()
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    private fun indexEvent(event: Event) {
        val key = EventKey(event.createdAt, event.id)
        allKeys.add(key)
        byKind.getOrPut(event.kind) { sortedEventKeySet() }.add(key)
        byPubkey.getOrPut(event.pubKey) { sortedEventKeySet() }.add(key)
        byPubkeyKind.getOrPut(event.pubKey to event.kind) { sortedEventKeySet() }.add(key)
        for (tag in event.tags) {
            if (tag.size >= 2) {
                byTag.getOrPut(tag[0] to tag[1]) { HashSet() }.add(event.id)
            }
        }
    }

    private fun unindexEvent(event: Event) {
        val key = EventKey(event.createdAt, event.id)
        allKeys.remove(key)
        byKind[event.kind]?.remove(key)
        byPubkey[event.pubKey]?.remove(key)
        byPubkeyKind[event.pubKey to event.kind]?.remove(key)
        for (tag in event.tags) {
            if (tag.size >= 2) {
                byTag[tag[0] to tag[1]]?.remove(event.id)
            }
        }
    }

    private fun emitCountByKind() {
        val counts = byKind.entries
            .map { CountByKindResult(it.key, it.value.size) }
            .sortedBy { it.kind }
        countByKindState.value = counts
    }

    private fun migrateFromRoomIfNeeded() {
        val legacyDb = context.getDatabasePath("citrine_database")
        if (!legacyDb.exists() || eventsDbi.size() > 0) return
        migrationState.value = true
        try {
            Log.i(Citrine.TAG, "Migrating legacy Room database to LMDB event store")
            val migrated = RoomToLmdbMigration.run(context, legacyDb) { event ->
                insertInternal(event)
            }
            Log.i(Citrine.TAG, "Migrated $migrated events from Room to LMDB")
            // Preserve the old DB as .bak so users can recover if migration goes wrong.
            val bak = File(legacyDb.parentFile, "citrine_database.bak")
            if (bak.exists()) bak.delete()
            legacyDb.renameTo(bak)
        } catch (e: Throwable) {
            Log.e(Citrine.TAG, "Room -> LMDB migration failed", e)
        } finally {
            migrationState.value = false
        }
    }

    // ------------------------------------------------------------------
    // Write API
    // ------------------------------------------------------------------

    suspend fun insertEvent(
        event: Event,
        connection: Connection?,
        sendEventToSubscriptions: Boolean = true,
    ) {
        val inserted = insertInternal(event)
        if (inserted && sendEventToSubscriptions) {
            EventSubscription.executeAll(event, connection)
        }
    }

    private fun insertInternal(event: Event): Boolean {
        indexLock.writeLock().lock()
        try {
            env.writeTxn { txn ->
                val existing = eventsDbi.get(txn, event.id)
                if (existing != null) {
                    unindexEvent(existing)
                }
                eventsDbi.put(txn, event.id, event)
                indexEvent(event)
            }
            emitCountByKind()
            return true
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        indexLock.writeLock().lock()
        try {
            env.writeTxn { txn ->
                for (id in ids) {
                    val existing = eventsDbi.get(txn, id) ?: continue
                    unindexEvent(existing)
                    eventsDbi.delete(txn, id)
                }
            }
            emitCountByKind()
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    suspend fun delete(ids: List<String>, pubkey: String) {
        if (ids.isEmpty()) return
        indexLock.writeLock().lock()
        try {
            env.writeTxn { txn ->
                for (id in ids) {
                    val existing = eventsDbi.get(txn, id) ?: continue
                    if (existing.pubKey != pubkey) continue
                    unindexEvent(existing)
                    eventsDbi.delete(txn, id)
                }
            }
            emitCountByKind()
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    suspend fun deleteById(id: String, pubkey: String): Int {
        var deleted = 0
        indexLock.writeLock().lock()
        try {
            env.writeTxn { txn ->
                val existing = eventsDbi.get(txn, id)
                if (existing != null && existing.pubKey == pubkey) {
                    unindexEvent(existing)
                    eventsDbi.delete(txn, id)
                    deleted = 1
                }
            }
            if (deleted > 0) emitCountByKind()
        } finally {
            indexLock.writeLock().unlock()
        }
        return deleted
    }

    suspend fun deleteAll() {
        indexLock.writeLock().lock()
        try {
            env.writeTxn { txn -> eventsDbi.clear(txn) }
            byKind.clear()
            byPubkey.clear()
            byPubkeyKind.clear()
            byTag.clear()
            allKeys.clear()
            emitCountByKind()
        } finally {
            indexLock.writeLock().unlock()
        }
    }

    suspend fun deleteAll(until: Long) {
        val toDelete = readLocked {
            allKeys.asSequence()
                .filter { it.createdAt <= until }
                .map { it.id }
                .toList()
        }
        delete(toDelete)
    }

    suspend fun deleteAll(until: Long, keepPubkeys: Array<String>) {
        val keep = keepPubkeys.toHashSet()
        val toDelete = readLocked {
            allKeys.asSequence()
                .filter { it.createdAt <= until }
                .mapNotNull { getUnlocked(it.id) }
                .filter { it.pubKey !in keep }
                .map { it.id }
                .toList()
        }
        delete(toDelete)
    }

    suspend fun deleteByKind(kind: Int) {
        val ids = readLocked { byKind[kind]?.map { it.id }.orEmpty() }
        delete(ids)
    }

    suspend fun deleteOldestByKind(kind: Int, pubkey: String) {
        val ids = readLocked {
            val set = byPubkeyKind[pubkey to kind] ?: return@readLocked emptyList()
            if (set.size <= 1) return@readLocked emptyList()
            // TreeSet is newest-first; drop(1) skips the newest and deletes the rest.
            set.drop(1).map { it.id }
        }
        delete(ids, pubkey)
    }

    suspend fun deleteEphemeralEvents(oneMinuteAgo: Long) {
        val ids = readLocked {
            (20000 until 30000).asSequence()
                .flatMap { kind -> byKind[kind]?.asSequence() ?: emptySequence() }
                .filter { it.createdAt < oneMinuteAgo }
                .map { it.id }
                .toList()
        }
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} ephemeral events")
            delete(ids)
        }
    }

    suspend fun deleteEventsWithExpirations(now: Long) {
        val ids = readLocked {
            byTag.asSequence()
                .filter { it.key.first == EXPIRATION_KEY }
                .flatMap { entry ->
                    val expiresAt = entry.key.second.toLongOrNull() ?: return@flatMap emptySequence()
                    if (expiresAt < now) entry.value.asSequence() else emptySequence()
                }
                .toList()
        }
        if (ids.isNotEmpty()) {
            Log.d(Citrine.TAG, "Deleting ${ids.size} expired events")
            delete(ids)
        }
    }

    // ------------------------------------------------------------------
    // Read API
    // ------------------------------------------------------------------

    fun getById(id: String): Event? = readLocked { getUnlocked(id) }

    private fun getUnlocked(id: String): Event? = env.readTxn { txn -> eventsDbi.get(txn, id) }

    fun getByIds(ids: List<String>): List<Event> = readLocked {
        env.readTxn { txn -> ids.mapNotNull { eventsDbi.get(txn, it) } }
    }

    fun getAllIds(): List<String> = readLocked { allKeys.map { it.id } }

    fun size(): Int = eventsDbi.size()

    fun countByKind(): Flow<List<CountByKindResult>> = countByKindState.asStateFlow()

    fun getContactLists(pubkey: String): List<Event> = readLocked {
        val keys = byPubkeyKind[pubkey to 3] ?: return@readLocked emptyList()
        env.readTxn { txn -> keys.mapNotNull { eventsDbi.get(txn, it.id) } }
    }

    fun getContactList(pubkey: String): Event? = readLocked {
        val first = byPubkeyKind[pubkey to 3]?.firstOrNull() ?: return@readLocked null
        env.readTxn { txn -> eventsDbi.get(txn, first.id) }
    }

    fun getAdvertisedRelayList(pubkey: String): Event? = readLocked {
        val first = byPubkeyKind[pubkey to 10002]?.firstOrNull() ?: return@readLocked null
        env.readTxn { txn -> eventsDbi.get(txn, first.id) }
    }

    fun getByKind(kind: Int, pubkey: String): List<String> = readLocked {
        byPubkeyKind[pubkey to kind]?.map { it.id }.orEmpty()
    }

    fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String> = readLocked {
        byPubkeyKind[pubkey to kind]
            ?.asSequence()
            ?.filter { it.createdAt >= createdAt }
            ?.map { it.id }
            ?.toList()
            .orEmpty()
    }

    fun getByKindKeyset(kind: Int, createdAt: Long?, id: String?, limit: Int): List<Event> = readLocked {
        val set = byKind[kind] ?: return@readLocked emptyList()
        val filtered = set.asSequence().filter { key ->
            if (createdAt == null) {
                true
            } else {
                key.createdAt < createdAt || (key.createdAt == createdAt && (id == null || key.id > id))
            }
        }
        env.readTxn { txn ->
            filtered.take(limit).mapNotNull { eventsDbi.get(txn, it.id) }.toList()
        }
    }

    fun getOldestReplaceable(kind: Int, pubkey: String, dTagValue: String): List<String> = readLocked {
        val candidateIds = byPubkeyKind[pubkey to kind]?.map { it.id }.orEmpty()
        val matching = env.readTxn { txn ->
            candidateIds.mapNotNull { eventsDbi.get(txn, it) }
                .filter { event -> event.tags.any { it.size > 1 && it[0] == "d" && it[1] == dTagValue } }
        }
        if (matching.size <= 1) return@readLocked emptyList()
        val newestCreated = matching.maxOf { it.createdAt }
        matching.filter { it.createdAt < newestCreated }.map { it.id }
    }

    fun getNewestReplaceable(kind: Int, pubkey: String, dTagValue: String, createdAt: Long): List<String> = readLocked {
        val candidateIds = byPubkeyKind[pubkey to kind]?.map { it.id }.orEmpty()
        env.readTxn { txn ->
            candidateIds.mapNotNull { eventsDbi.get(txn, it) }
                .filter { event ->
                    event.createdAt >= createdAt &&
                        event.tags.any { it.size > 1 && it[0] == "d" && it[1] == dTagValue }
                }
                .map { it.id }
        }
    }

    fun getDeletedEvents(eTagValue: String): List<String> = readLocked {
        val candidateIds = byTag[TAG_E to eTagValue] ?: return@readLocked emptyList()
        env.readTxn { txn ->
            candidateIds.mapNotNull { eventsDbi.get(txn, it) }
                .filter { it.kind == 5 }
                .map { it.id }
        }
    }

    fun getDeletedEventsByATag(aTagValue: String): List<Long> = readLocked {
        val candidateIds = byTag[TAG_A to aTagValue] ?: return@readLocked emptyList()
        env.readTxn { txn ->
            candidateIds.mapNotNull { eventsDbi.get(txn, it) }
                .filter { it.kind == 5 }
                .map { it.createdAt }
        }
    }

    fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<Event> = readLocked {
        env.readTxn { txn ->
            allKeys.asSequence()
                .filter { it.createdAt in createdAtFrom..createdAtTo }
                .drop(offset)
                .take(limit)
                .mapNotNull { eventsDbi.get(txn, it.id) }
                .toList()
        }
    }

    fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<Event> = readLocked {
        val keys = if (kind == -1) byPubkey[pubkey] else byPubkeyKind[pubkey to kind]
        keys ?: return@readLocked emptyList()
        env.readTxn { txn ->
            keys.asSequence()
                .filter { it.createdAt in createdAtFrom..createdAtTo }
                .drop(offset)
                .take(limit)
                .mapNotNull { eventsDbi.get(txn, it.id) }
                .toList()
        }
    }

    fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<Event> = readLocked {
        val keys = if (pubkey.isBlank()) byKind[kind] else byPubkeyKind[pubkey to kind]
        keys ?: return@readLocked emptyList()
        env.readTxn { txn ->
            keys.asSequence()
                .filter { it.createdAt in createdAtFrom..createdAtTo }
                .drop(offset)
                .take(limit)
                .mapNotNull { eventsDbi.get(txn, it.id) }
                .toList()
        }
    }

    // ------------------------------------------------------------------
    // Filter-driven query API (used by EventRepository)
    // ------------------------------------------------------------------

    fun query(filter: EventFilter): List<Event> = readLocked {
        val candidateKeys = candidateKeysFor(filter)
        val limit = filter.limit ?: Int.MAX_VALUE
        env.readTxn { txn ->
            val seq = candidateKeys.asSequence()
                .mapNotNull { eventsDbi.get(txn, it.id) }
                .filter { filter.test(it) }
            seq.take(limit).toList()
        }
    }

    fun count(filter: EventFilter): Int = query(filter).size

    private fun candidateKeysFor(filter: EventFilter): Iterable<EventKey> {
        // Prefer the most selective index available. IDs > tags > authors+kinds > kinds.
        if (filter.ids.isNotEmpty()) {
            return filter.ids.mapNotNull { id -> getUnlocked(id) }
                .map { EventKey(it.createdAt, it.id) }
                .sortedWith(EventKey.NEWEST_FIRST)
        }

        val tagIds: Set<String>? = if (filter.tags.isNotEmpty()) {
            var acc: MutableSet<String>? = null
            for ((name, values) in filter.tags) {
                val union = HashSet<String>()
                for (v in values) {
                    byTag[name to v]?.let { union.addAll(it) }
                }
                acc = if (acc == null) union else acc.apply { retainAll(union) }
                if (acc.isEmpty()) return emptyList()
            }
            acc
        } else {
            null
        }

        val authorKeys: List<EventKey>? = when {
            filter.authors.isNotEmpty() && filter.kinds.isNotEmpty() ->
                filter.authors.flatMap { a -> filter.kinds.flatMap { k -> byPubkeyKind[a to k].orEmpty() } }
                    .sortedWith(EventKey.NEWEST_FIRST)
            filter.authors.isNotEmpty() ->
                filter.authors.flatMap { byPubkey[it].orEmpty() }.sortedWith(EventKey.NEWEST_FIRST)
            filter.kinds.isNotEmpty() ->
                filter.kinds.flatMap { byKind[it].orEmpty() }.sortedWith(EventKey.NEWEST_FIRST)
            else -> null
        }

        return when {
            tagIds != null && authorKeys != null -> authorKeys.filter { it.id in tagIds }
            tagIds != null -> tagIds.mapNotNull { id ->
                getUnlocked(id)?.let { EventKey(it.createdAt, it.id) }
            }.sortedWith(EventKey.NEWEST_FIRST)
            authorKeys != null -> authorKeys
            else -> allKeys
        }
    }

    private inline fun <R> readLocked(block: () -> R): R {
        indexLock.readLock().lock()
        try {
            return block()
        } finally {
            indexLock.readLock().unlock()
        }
    }

    fun close() {
        env.close()
    }

    companion object {
        private const val EXPIRATION_KEY = "expiration"
        private const val TAG_E = "e"
        private const val TAG_A = "a"

        @Volatile
        private var INSTANCE: EventStore? = null

        @Volatile
        private var HISTORY_INSTANCE: EventStore? = null

        fun getInstance(context: Context): EventStore {
            val existing = INSTANCE
            if (existing != null) return existing
            synchronized(this) {
                val again = INSTANCE
                if (again != null) return again
                val created = EventStore(context.applicationContext, "citrine_lmdb", runLegacyMigration = true)
                INSTANCE = created
                return created
            }
        }

        fun getHistoryInstance(context: Context): EventStore {
            val existing = HISTORY_INSTANCE
            if (existing != null) return existing
            synchronized(this) {
                val again = HISTORY_INSTANCE
                if (again != null) return again
                val created = EventStore(context.applicationContext, "citrine_lmdb_history", runLegacyMigration = false)
                HISTORY_INSTANCE = created
                return created
            }
        }

        private fun sortedEventKeySet(): TreeSet<EventKey> = TreeSet(EventKey.NEWEST_FIRST)
    }
}

/** (createdAt, id) key used by secondary indexes. Sorts newest first, ties broken by id ASC. */
data class EventKey(val createdAt: Long, val id: String) : Comparable<EventKey> {
    override fun compareTo(other: EventKey): Int = NEWEST_FIRST.compare(this, other)

    companion object {
        val NEWEST_FIRST: Comparator<EventKey> = Comparator { a, b ->
            val c = b.createdAt.compareTo(a.createdAt)
            if (c != 0) c else a.id.compareTo(b.id)
        }
    }
}

private object EventCodec : LmdbCodec<String, Event> {
    override fun encodeKey(key: String): String = key
    override fun decodeKey(raw: String): String = raw
    override fun encodeValue(value: Event): String = value.toJson().replace("\n", " ")
    override fun decodeValue(raw: String): Event = Event.fromJson(raw)
}
