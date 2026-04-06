package com.greenart7c3.citrine.database

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventSubscription
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [EventStore] implementation backed by nostrdb — the high-performance LMDB-based
 * Nostr event store developed by Damus (https://github.com/damus-io/nostrdb).
 *
 * ### Prerequisites
 * Before building run `scripts/setup_nostrdb.sh` to clone the C sources into
 * `app/src/main/cpp/nostrdb/`. The NDK build is configured in
 * `app/src/main/cpp/CMakeLists.txt` and wired into `app/build.gradle.kts`.
 *
 * ### Data model
 * nostrdb stores events as LMDB records using a FlatBuffers-encoded note format.
 * Queries use NIP-01 JSON filters via [NostrDb.nQuery]. Events are serialised to
 * JSON via nostrdb's own `ndb_note_json()` then parsed back into [EventWithTags].
 *
 * @param dbPath Directory path for the LMDB database files.
 * @param mapSize LMDB memory-map size in bytes (default 1 GiB).
 */
class NostrDbEventStore(
    private val dbPath: String,
    private val mapSize: Long = 1L * 1024 * 1024 * 1024,
) : EventStore {

    private val handle: Long
    private val kindCountFlow = MutableStateFlow<List<EventDao.CountResult>>(emptyList())

    init {
        File(dbPath).mkdirs()
        handle = NostrDb.nInit(dbPath, mapSize)
        check(handle != 0L) { "nostrdb: failed to open database at '$dbPath'" }
        refreshCountByKind()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun refreshCountByKind() {
        val json = NostrDb.nCountByKind(handle)
        kindCountFlow.value = parseCountByKind(json)
    }

    /** Convert an [EventFilter] to a NIP-01 JSON filter string for nostrdb. */
    private fun EventFilter.toNip01Json(): String {
        val sb = StringBuilder("{")
        var first = true
        fun sep() {
            if (!first) sb.append(',')
            first = false
        }

        if (ids.isNotEmpty()) {
            sep()
            sb.append("\"ids\":[")
            sb.append(ids.joinToString(",") { "\"$it\"" })
            sb.append(']')
        }
        if (authors.isNotEmpty()) {
            sep()
            sb.append("\"authors\":[")
            sb.append(authors.joinToString(",") { "\"$it\"" })
            sb.append(']')
        }
        if (kinds.isNotEmpty()) {
            sep()
            sb.append("\"kinds\":[")
            sb.append(kinds.joinToString(","))
            sb.append(']')
        }
        since?.let {
            sep()
            sb.append("\"since\":$it")
        }
        until?.let {
            sep()
            sb.append("\"until\":$it")
        }
        limit?.let {
            sep()
            sb.append("\"limit\":$it")
        }
        search?.let {
            sep()
            sb.append("\"search\":")
            sb.append(MAPPER.writeValueAsString(it))
        }
        tags.forEach { (k, values) ->
            sep()
            sb.append("\"#$k\":[")
            sb.append(values.joinToString(",") { "\"$it\"" })
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun queryRaw(filter: EventFilter): Array<String> = NostrDb.nQuery(handle, filter.toNip01Json(), filter.limit ?: DEFAULT_LIMIT)

    private fun queryEvents(filter: EventFilter): List<EventWithTags> = queryRaw(filter).mapNotNull { parseEventJson(it) }

    // ── Dynamic filter queries ───────────────────────────────────────────────

    override fun getEvents(filter: EventFilter): List<EventWithTags> = queryEvents(filter)

    override fun countEvents(filter: EventFilter): Int = NostrDb.nCount(handle, filter.toNip01Json(), filter.limit ?: COUNT_LIMIT)

    // ── Reactive count for UI ────────────────────────────────────────────────

    override fun countByKind(): Flow<List<EventDao.CountResult>> = kindCountFlow.asStateFlow()

    // ── Point lookups ────────────────────────────────────────────────────────

    override suspend fun getById(id: String): EventWithTags? = NostrDb.nQuery(handle, "{\"ids\":[\"$id\"]}", 1)
        .firstOrNull()?.let { parseEventJson(it) }

    override suspend fun getByIds(ids: List<String>): List<EventWithTags> {
        if (ids.isEmpty()) return emptyList()
        val json = "{\"ids\":[${ids.joinToString(",") { "\"$it\"" }}]}"
        return NostrDb.nQuery(handle, json, ids.size).mapNotNull { parseEventJson(it) }
    }

    override suspend fun getAllIds(): List<String> = NostrDb.nAllIds(handle).toList()

    override suspend fun getByKind(kind: Int, pubkey: String): List<String> = NostrDb.nQuery(handle, "{\"kinds\":[$kind],\"authors\":[\"$pubkey\"]}", COUNT_LIMIT)
        .mapNotNull { parseEventJson(it)?.event?.id }

    override suspend fun getByKindNewest(kind: Int, pubkey: String, createdAt: Long): List<String> = NostrDb.nQuery(
        handle,
        "{\"kinds\":[$kind],\"authors\":[\"$pubkey\"],\"since\":$createdAt}",
        COUNT_LIMIT,
    ).mapNotNull { parseEventJson(it)?.event?.id }

    override suspend fun getContactList(pubkey: String): EventWithTags? = NostrDb.nQuery(handle, "{\"kinds\":[3],\"authors\":[\"$pubkey\"],\"limit\":1}", 1)
        .firstOrNull()?.let { parseEventJson(it) }

    override suspend fun getContactLists(pubkey: String): List<EventWithTags> = NostrDb.nQuery(handle, "{\"kinds\":[3],\"authors\":[\"$pubkey\"]}", COUNT_LIMIT)
        .mapNotNull { parseEventJson(it) }

    override suspend fun getAdvertisedRelayList(pubkey: String): EventWithTags? = NostrDb.nQuery(handle, "{\"kinds\":[10002],\"authors\":[\"$pubkey\"],\"limit\":1}", 1)
        .firstOrNull()?.let { parseEventJson(it) }

    override suspend fun getDeletedEvents(eTagValue: String): List<String> = NostrDb.nQuery(handle, "{\"kinds\":[5],\"#e\":[\"$eTagValue\"]}", COUNT_LIMIT)
        .mapNotNull { parseEventJson(it)?.event?.id }

    override suspend fun getDeletedEventsByATag(aTagValue: String): List<Long> = NostrDb.nQuery(
        handle,
        "{\"kinds\":[5],\"#a\":[\"${aTagValue.replace("\"", "\\\"")}\"]}",
        COUNT_LIMIT,
    ).mapNotNull { parseEventJson(it)?.event?.createdAt }

    override suspend fun getOldestReplaceable(
        kind: Int,
        pubkey: String,
        dTagValue: String,
    ): List<String> {
        val events = NostrDb.nQuery(
            handle,
            "{\"kinds\":[$kind],\"authors\":[\"$pubkey\"],\"#d\":[\"${dTagValue.replace("\"", "\\\"")}\"]}",
            COUNT_LIMIT,
        ).mapNotNull { parseEventJson(it) }
            .sortedByDescending { it.event.createdAt }
        // Return IDs of all but the newest (i.e. the ones to delete)
        return events.drop(1).map { it.event.id }
    }

    override suspend fun getNewestReplaceable(
        kind: Int,
        pubkey: String,
        dTagValue: String,
        createdAt: Long,
    ): List<String> = NostrDb.nQuery(
        handle,
        "{\"kinds\":[$kind],\"authors\":[\"$pubkey\"],\"since\":$createdAt,\"#d\":[\"${dTagValue.replace("\"", "\\\"")}\"]}",
        COUNT_LIMIT,
    ).mapNotNull { parseEventJson(it)?.event?.id }

    override suspend fun getByKindKeyset(
        kind: Int,
        createdAt: Long?,
        id: String?,
        limit: Int,
    ): List<EventWithTags> {
        val until = createdAt ?: Long.MAX_VALUE
        // Fetch slightly more than needed to handle ties at the cursor boundary
        val raw = NostrDb.nQuery(
            handle,
            "{\"kinds\":[$kind],\"until\":$until,\"limit\":${limit + 20}}",
            limit + 20,
        ).mapNotNull { parseEventJson(it) }
            .sortedWith(compareByDescending<EventWithTags> { it.event.createdAt }.thenBy { it.event.id })

        val filtered = if (id != null && createdAt != null) {
            // Drop events already delivered (same createdAt boundary, id ≤ cursor)
            raw.dropWhile { ev ->
                ev.event.createdAt == createdAt && ev.event.id <= id
            }
        } else {
            raw
        }
        return filtered.take(limit)
    }

    // ── ContentProvider queries ──────────────────────────────────────────────

    override suspend fun getEventsByDateRange(
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        val since = if (createdAtFrom == Long.MIN_VALUE) null else createdAtFrom
        val until = if (createdAtTo == Long.MAX_VALUE) null else createdAtTo
        val fj = buildSimpleFilter(since = since, until = until, limit = limit + offset)
        return NostrDb.nQuery(handle, fj, limit + offset)
            .mapNotNull { parseEventJson(it) }
            .sortedByDescending { it.event.createdAt }
            .drop(offset).take(limit)
    }

    override suspend fun getEventsByPubkey(
        pubkey: String,
        kind: Int,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        val since = if (createdAtFrom == Long.MIN_VALUE) null else createdAtFrom
        val until = if (createdAtTo == Long.MAX_VALUE) null else createdAtTo
        val kindPart = if (kind == -1) "" else "\"kinds\":[$kind],"
        val sincePart = since?.let { "\"since\":$it," } ?: ""
        val untilPart = until?.let { "\"until\":$it," } ?: ""
        val fj = "{${kindPart}\"authors\":[\"$pubkey\"],${sincePart}${untilPart}\"limit\":${limit + offset}}"
        return NostrDb.nQuery(handle, fj, limit + offset)
            .mapNotNull { parseEventJson(it) }
            .sortedByDescending { it.event.createdAt }
            .drop(offset).take(limit)
    }

    override suspend fun getEventsByKind(
        kind: Int,
        pubkey: String,
        createdAtFrom: Long,
        createdAtTo: Long,
        limit: Int,
        offset: Int,
    ): List<EventWithTags> {
        val since = if (createdAtFrom == Long.MIN_VALUE) null else createdAtFrom
        val until = if (createdAtTo == Long.MAX_VALUE) null else createdAtTo
        val authorPart = if (pubkey.isEmpty()) "" else "\"authors\":[\"$pubkey\"],"
        val sincePart = since?.let { "\"since\":$it," } ?: ""
        val untilPart = until?.let { "\"until\":$it," } ?: ""
        val fj = "{\"kinds\":[$kind],${authorPart}${sincePart}${untilPart}\"limit\":${limit + offset}}"
        return NostrDb.nQuery(handle, fj, limit + offset)
            .mapNotNull { parseEventJson(it) }
            .sortedByDescending { it.event.createdAt }
            .drop(offset).take(limit)
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    override suspend fun insertEventWithTags(
        dbEvent: EventWithTags,
        connection: Connection?,
        sendEventToSubscriptions: Boolean,
    ): Boolean {
        val json = dbEvent.toNostrJson()
        val inserted = NostrDb.nIngest(handle, json)
        if (inserted) {
            if (sendEventToSubscriptions && connection != null) {
                EventSubscription.executeAll(dbEvent, connection)
            }
            refreshCountByKind()
        }
        return inserted
    }

    // ── Deletes ──────────────────────────────────────────────────────────────

    override suspend fun deleteById(id: String, pubkey: String): Int {
        // Verify ownership before deleting
        val event = getById(id) ?: return 0
        if (event.event.pubkey != pubkey) return 0
        val result = NostrDb.nDeleteById(handle, id)
        if (result > 0) refreshCountByKind()
        return result
    }

    override suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        NostrDb.nDeleteByIds(handle, ids.toTypedArray())
        refreshCountByKind()
    }

    override suspend fun delete(ids: List<String>, pubkey: String) {
        if (ids.isEmpty()) return
        // Filter to IDs that actually belong to pubkey
        val owned = getByIds(ids)
            .filter { it.event.pubkey == pubkey }
            .map { it.event.id }
        if (owned.isNotEmpty()) {
            NostrDb.nDeleteByIds(handle, owned.toTypedArray())
            refreshCountByKind()
        }
    }

    override suspend fun deleteAll() {
        val ids = NostrDb.nAllIds(handle)
        if (ids.isNotEmpty()) NostrDb.nDeleteByIds(handle, ids)
        refreshCountByKind()
    }

    override suspend fun deleteAll(until: Long) {
        val ids = NostrDb.nQuery(handle, "{\"until\":$until}", COUNT_LIMIT)
            .mapNotNull { parseEventJson(it)?.event?.id }
            .toTypedArray()
        if (ids.isNotEmpty()) NostrDb.nDeleteByIds(handle, ids)
        refreshCountByKind()
    }

    override suspend fun deleteAll(until: Long, pubKeys: Array<String>) {
        // Query all events up to `until`, keep those whose pubkey IS in pubKeys
        val toDelete = NostrDb.nQuery(handle, "{\"until\":$until}", COUNT_LIMIT)
            .mapNotNull { parseEventJson(it) }
            .filter { it.event.pubkey !in pubKeys }
            .map { it.event.id }
            .toTypedArray()
        if (toDelete.isNotEmpty()) NostrDb.nDeleteByIds(handle, toDelete)
        refreshCountByKind()
    }

    override suspend fun deleteByKind(kind: Int) {
        val ids = NostrDb.nQuery(handle, "{\"kinds\":[$kind]}", COUNT_LIMIT)
            .mapNotNull { parseEventJson(it)?.event?.id }
            .toTypedArray()
        if (ids.isNotEmpty()) NostrDb.nDeleteByIds(handle, ids)
        refreshCountByKind()
    }

    override suspend fun deleteOldestByKind(kind: Int, pubkey: String) {
        val events = NostrDb.nQuery(
            handle,
            "{\"kinds\":[$kind],\"authors\":[\"$pubkey\"]}",
            COUNT_LIMIT,
        ).mapNotNull { parseEventJson(it) }
            .sortedByDescending { it.event.createdAt }
        // Keep the newest, delete the rest
        val toDelete = events.drop(1).map { it.event.id }.toTypedArray()
        if (toDelete.isNotEmpty()) NostrDb.nDeleteByIds(handle, toDelete)
    }

    override suspend fun deleteEphemeralEvents(oneMinuteAgo: Long) {
        // Ephemeral kinds: 20000–29999. Query events older than oneMinuteAgo,
        // then filter by kind range in Kotlin (nostrdb doesn't support kind ranges).
        val toDelete = NostrDb.nQuery(handle, "{\"until\":$oneMinuteAgo}", COUNT_LIMIT)
            .mapNotNull { parseEventJson(it) }
            .filter { it.event.kind in 20000..29999 }
            .map { it.event.id }
            .toTypedArray()
        if (toDelete.isNotEmpty()) {
            Log.d(TAG, "Deleting ${toDelete.size} ephemeral events")
            NostrDb.nDeleteByIds(handle, toDelete)
        }
    }

    override suspend fun deleteEventsWithExpirations(now: Long) {
        // Find events that have an 'expiration' tag whose value is < now.
        // nostrdb can filter by tag name+value but not by numeric value range,
        // so we load candidates in Kotlin. We bound by `until=now` to limit scope.
        val toDelete = NostrDb.nQuery(handle, "{\"until\":$now}", COUNT_LIMIT)
            .mapNotNull { parseEventJson(it) }
            .filter { ewt ->
                ewt.tags.any { tag ->
                    tag.col0Name == "expiration" &&
                        tag.col1Value?.toLongOrNull()?.let { it < now } == true
                }
            }
            .map { it.event.id }
            .toTypedArray()
        if (toDelete.isNotEmpty()) {
            Log.d(TAG, "Deleting ${toDelete.size} expired events")
            NostrDb.nDeleteByIds(handle, toDelete)
            refreshCountByKind()
        }
    }

    // ── Paging ───────────────────────────────────────────────────────────────

    override fun createPagingSource(kind: Int): PagingSource<EventKey, EventWithTags> = NostrDbPagingSource(handle, kind)

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NostrDbEventStore"
        private const val DEFAULT_LIMIT = 500
        private const val COUNT_LIMIT = 100_000

        private val MAPPER = jacksonObjectMapper()

        /**
         * Parses a raw Nostr event JSON string (as returned by `ndb_note_json`)
         * into an [EventWithTags].
         */
        fun parseEventJson(json: String): EventWithTags? = try {
            val node = MAPPER.readTree(json)
            val id = node["id"]?.textValue() ?: return null
            val pubkey = node["pubkey"]?.textValue() ?: return null
            val createdAt = node["created_at"]?.longValue() ?: return null
            val kind = node["kind"]?.intValue() ?: return null
            val content = node["content"]?.textValue() ?: ""
            val sig = node["sig"]?.textValue() ?: return null

            val tagEntities = node["tags"]?.mapIndexed { index, tagNode ->
                val arr = tagNode.map { it.textValue() ?: "" }
                TagEntity(
                    position = index,
                    col0Name = arr.getOrNull(0),
                    col1Value = arr.getOrNull(1),
                    col2Differentiator = arr.getOrNull(2),
                    col3Amount = arr.getOrNull(3),
                    col4Plus = if (arr.size > 4) arr.subList(4, arr.size) else emptyList(),
                    kind = kind,
                    pkEvent = id,
                )
            } ?: emptyList()

            EventWithTags(
                event = EventEntity(id, pubkey, createdAt, kind, content, sig),
                tags = tagEntities,
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseEventJson failed", e)
            null
        }

        private fun parseCountByKind(json: String): List<EventDao.CountResult> = try {
            MAPPER.readTree(json).map { item ->
                EventDao.CountResult().apply {
                    kind = item["kind"].intValue()
                    count = item["count"].intValue()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseCountByKind failed", e)
            emptyList()
        }

        private fun EventWithTags.toNostrJson(): String {
            val tagsArr = tags.sortedBy { it.position }.map { tag ->
                listOfNotNull(tag.col0Name, tag.col1Value, tag.col2Differentiator, tag.col3Amount)
                    .plus(tag.col4Plus)
            }
            return MAPPER.writeValueAsString(
                mapOf(
                    "id" to event.id,
                    "pubkey" to event.pubkey,
                    "created_at" to event.createdAt,
                    "kind" to event.kind,
                    "content" to event.content,
                    "tags" to tagsArr,
                    "sig" to event.sig,
                ),
            )
        }

        private fun buildSimpleFilter(
            since: Long? = null,
            until: Long? = null,
            limit: Int? = null,
        ): String {
            val parts = mutableListOf<String>()
            since?.let { parts += "\"since\":$it" }
            until?.let { parts += "\"until\":$it" }
            limit?.let { parts += "\"limit\":$it" }
            return "{${parts.joinToString(",")}}"
        }
    }
}

// ── Paging source ─────────────────────────────────────────────────────────────

private class NostrDbPagingSource(
    private val handle: Long,
    private val kind: Int,
) : PagingSource<EventKey, EventWithTags>() {

    override suspend fun load(params: LoadParams<EventKey>): LoadResult<EventKey, EventWithTags> {
        val key = params.key
        val fetchSize = params.loadSize + 20 // overfetch to handle cursor boundary ties

        val untilPart = key?.let { ",\"until\":${it.createdAt}" } ?: ""
        val filterJson = "{\"kinds\":[$kind]$untilPart,\"limit\":$fetchSize}"

        val all = NostrDb.nQuery(handle, filterJson, fetchSize)
            .mapNotNull { NostrDbEventStore.parseEventJson(it) }
            .sortedWith(compareByDescending<EventWithTags> { it.event.createdAt }.thenBy { it.event.id })

        val page = if (key != null) {
            all.dropWhile { ev ->
                ev.event.createdAt == key.createdAt && ev.event.id <= key.id
            }
        } else {
            all
        }.take(params.loadSize)

        val nextKey = if (page.size < params.loadSize) {
            null
        } else {
            page.last().event.let { EventKey(it.createdAt, it.id) }
        }

        return LoadResult.Page(data = page, prevKey = null, nextKey = nextKey)
    }

    override fun getRefreshKey(state: PagingState<EventKey, EventWithTags>): EventKey? = null
}
