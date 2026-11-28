package com.greenart7c3.citrine.server

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventEntity
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.IdAndCreatedAt
import com.greenart7c3.citrine.database.TagDeduplicator
import com.greenart7c3.citrine.database.TagEntity
import com.greenart7c3.citrine.json.JacksonMapper
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.TimeUtils
import java.io.StringWriter
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val TAG_KEY_REGEX = Regex("^[a-zA-Z0-9]+$")
private val JACKSON_NODE_FACTORY = JacksonMapper.mapper.nodeFactory

object EventRepository {
    private const val QUERY_BATCH_SIZE = 500

    // Content loaded at once per content chunk: one max-size event.
    private const val BATCH_CONTENT_BUDGET_CHARS = 1024 * 1024

    // Tags loaded at once per content chunk (~3 MB of TagEntity objects).
    internal const val CHUNK_TAG_BUDGET = 10_000

    // Hard per-event ceilings so a single pathological event (unusually huge tag list or
    // multi-MB tag strings) can never exhaust the 512 MB heap on read.
    internal const val MAX_EVENT_TAGS = 50_000
    internal const val MAX_EVENT_TAG_CHARS = 8 * 1024 * 1024

    // App-wide cap on content chunks loaded at once, whatever the number of connections and
    // subscriptions: about HISTORY_CHUNK_PERMITS x (1M chars of content + 10k tags + their
    // frames), ~40 MB, or one oversized event's tags instead of the 10k.
    private const val HISTORY_CHUNK_PERMITS = 4
    private val historyChunkPermits = Semaphore(HISTORY_CHUNK_PERMITS)

    enum class SelectMode {
        FULL_EVENTS,
        COUNT,
        IDS_AND_CREATED_AT,
    }

    fun createQuery(
        filter: EventFilter,
        mode: SelectMode,
        cursorCreatedAt: Long? = null,
        cursorId: String? = null,
        batchLimit: Int? = null,
    ): Pair<String, List<Any>> {
        val sql = QueryParts()

        // --- EventEntity filters ---
        sql.appendEventFilters(filter)

        // --- Add each tag as an AND EXISTS in WHERE ---
        // Note: no JOIN against TagEntity. The EXISTS predicates below do all the tag
        // filtering; a join would multiply each event row by its tag count and force a
        // DISTINCT over full-width rows (including content) to dedupe them again, which
        // is dramatically slower.
        filter.tags.forEach { tag -> sql.appendTagExists(tag.key, tag.value, filter.kinds) }

        // Never load rows whose content exceeds the size cap: a single multi-MB row can
        // exhaust the heap while being serialized (issue #187).
        if (mode == SelectMode.FULL_EVENTS && Settings.maxEventSize > 0) {
            sql.appendWhere("length(EventEntity.content) <= ?")
            sql.params.add(Settings.maxEventSize)
        }

        // Keyset pagination cursor: continues strictly after the previous page's last row
        // under the non-search ordering "createdAt DESC, id ASC", index-backed by
        // idx_event_created_id. Never combine with searchKeywords (rowid ordering).
        if (cursorCreatedAt != null && cursorId != null) {
            sql.appendWhere("(EventEntity.createdAt < ? OR (EventEntity.createdAt = ? AND EventEntity.id > ?))")
            sql.params.add(cursorCreatedAt)
            sql.params.add(cursorCreatedAt)
            sql.params.add(cursorId)
        }

        val query = sql.select(mode, filter)

        if (batchLimit != null) {
            query.append(" LIMIT ?")
            sql.params.add(batchLimit)
        } else {
            filter.limit?.let {
                query.append(" LIMIT ?")
                sql.params.add(it)
            }
        }
        return Pair(query.toString(), sql.params)
    }

    private class QueryParts {
        val params = mutableListOf<Any>()
        val joinClause = StringBuilder()
        val whereClause = StringBuilder()

        fun appendWhere(predicate: CharSequence) {
            if (whereClause.isEmpty()) {
                whereClause.append(" WHERE ")
            } else {
                whereClause.append(" AND ")
            }
            whereClause.append(predicate)
        }

        fun appendEventFilters(filter: EventFilter) {
            // Exclude events whose NIP-40 expiration timestamp is in the past.
            appendWhere("(EventEntity.expiresAt IS NULL OR EventEntity.expiresAt >= ?)")
            params.add(TimeUtils.now())

            filter.since?.let {
                appendWhere("EventEntity.createdAt >= ?")
                params.add(it)
            }

            filter.until?.let {
                appendWhere("EventEntity.createdAt <= ?")
                params.add(it)
            }

            if (filter.ids.isNotEmpty()) {
                val placeholders = filter.ids.joinToString(",") { "?" }
                appendWhere("EventEntity.id IN ($placeholders)")
                params.addAll(filter.ids)
            }

            if (filter.authors.isNotEmpty()) {
                val placeholders = filter.authors.joinToString(",") { "?" }
                appendWhere("EventEntity.pubkey IN ($placeholders)")
                params.addAll(filter.authors)
            }

            if (filter.searchKeywords.isNotEmpty()) {
                joinClause.append(" INNER JOIN event_fts ON event_fts.rowid = EventEntity.rowid")
                val searchParam = filter.searchKeywords.joinToString(" ") { "\"$it\"" }
                appendWhere("event_fts MATCH ?")
                params.add(searchParam)
            }

            if (filter.kinds.isNotEmpty()) {
                val placeholders = filter.kinds.joinToString(",") { "?" }
                appendWhere("EventEntity.kind IN ($placeholders)")
                params.addAll(filter.kinds)
            }
        }

        fun appendTagExists(key: String, values: Set<String>, kinds: Set<Int>) {
            val safeTagKey = key.takeIf { it.matches(TAG_KEY_REGEX) }
                ?: throw IllegalArgumentException("Invalid tag key: $key")

            val existsClause = StringBuilder()
                .append("EXISTS (SELECT 1 FROM TagEntity WHERE pkEvent = EventEntity.id AND col0Name = ?")
            params.add(safeTagKey)

            if (values.isNotEmpty()) {
                val valuePlaceholders = values.joinToString(",") { "?" }
                existsClause.append(" AND col1Value IN (").append(valuePlaceholders).append(")")
                params.addAll(values)
            }

            if (kinds.isNotEmpty()) {
                val kindPlaceholders = kinds.joinToString(",") { "?" }
                existsClause.append(" AND kind IN (").append(kindPlaceholders).append(")")
                params.addAll(kinds)
            }

            existsClause.append(")")

            appendWhere(existsClause)
        }

        fun select(mode: SelectMode, filter: EventFilter): StringBuilder {
            // --- Build SQL ---
            val orderBy = if (filter.searchKeywords.isNotEmpty()) {
                "EventEntity.rowid DESC"
            } else {
                "EventEntity.createdAt DESC, EventEntity.id ASC"
            }

            // The FTS join is 1:1 (rowid = rowid) and the tag filters use EXISTS, so no row
            // in the result is ever duplicated and no DISTINCT is needed in any mode.
            val query = StringBuilder()
            when (mode) {
                SelectMode.COUNT -> {
                    query.append("SELECT COUNT(*) FROM EventEntity EventEntity")
                    query.append(joinClause)
                    query.append(whereClause)
                }
                SelectMode.FULL_EVENTS -> {
                    query.append("SELECT EventEntity.* FROM EventEntity EventEntity")
                    query.append(joinClause)
                    query.append(whereClause)
                    query.append(" ORDER BY ").append(orderBy)
                }
                SelectMode.IDS_AND_CREATED_AT -> {
                    query.append("SELECT EventEntity.id AS id, EventEntity.createdAt AS createdAt FROM EventEntity EventEntity")
                    query.append(joinClause)
                    query.append(whereClause)
                    query.append(" ORDER BY ").append(orderBy)
                }
            }
            return query
        }
    }

    fun query(
        database: AppDatabase,
        filter: EventFilter,
    ): List<EventWithTags> {
        val query = createQuery(filter, SelectMode.FULL_EVENTS)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().getEvents(rawSql)
    }

    fun countQuery(
        database: AppDatabase,
        filter: EventFilter,
    ): Int {
        val query = createQuery(filter, SelectMode.COUNT)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().count(rawSql)
    }

    /**
     * Streams [filter]'s full-event matches to [onBatch] in keyset-paginated pages of at
     * most [QUERY_BATCH_SIZE] rows (or the remainder of [clientLimit], whichever is
     * smaller), so the whole match set is never materialized in memory. Each page is
     * selected as ids only and its content loaded by [emitByIds]. Returns the number of
     * events sent. [awaitRoom] runs before each chunk, see [emitByIds].
     */
    suspend fun batchedQuery(
        database: AppDatabase,
        filter: EventFilter,
        clientLimit: Int?,
        awaitRoom: suspend () -> Unit = {},
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ): Int {
        var sent = 0
        var cursorCreatedAt: Long? = null
        var cursorId: String? = null
        while (true) {
            // The queries below block uninterruptibly, so this is where a subscription
            // whose client sent CLOSE or disconnected stops costing memory and I/O.
            currentCoroutineContext().ensureActive()

            val effective = minOf(QUERY_BATCH_SIZE, clientLimit?.let { it - sent } ?: Int.MAX_VALUE)
            if (effective <= 0) break

            // Ids only: with an IN list on authors/kinds SQLite sorts the candidates in a temp
            // B-tree (native memory, temp_store=MEMORY), and full rows put up to
            // QUERY_BATCH_SIZE x maxEventSize of content there, on top of the same in the heap.
            val query = createQuery(filter, SelectMode.IDS_AND_CREATED_AT, cursorCreatedAt, cursorId, effective)
            val page = database.eventDao().getIdsAndCreatedAt(SimpleSQLiteQuery(query.first, query.second.toTypedArray()))
            if (page.isEmpty()) break

            // Counts what was sent, not the page: skipped ids must not eat into the client's limit.
            sent += emitByIds(database, page.map { it.id }, awaitRoom, onBatch)
            if (page.size < effective) break

            cursorCreatedAt = page.last().createdAt
            cursorId = page.last().id
        }
        return sent
    }

    /**
     * Loads the events of [ids] and hands them to [onBatch] in [ids] order, in chunks of at
     * most [BATCH_CONTENT_BUDGET_CHARS] of content (an event above the budget gets a chunk of
     * its own), so a page of maximum-size events is never in memory at once. Events above
     * [Settings.maxEventSize] or deleted since the ids were read are skipped. Returns the
     * number of events handed to [onBatch].
     *
     * Each chunk is loaded and handed over under one of the app-wide [historyChunkPermits].
     * [awaitRoom] runs before the permit is taken: it should wait until the receiving client
     * can take the chunk, so a client that is slow or not reading does not hold a permit
     * every other connection needs.
     */
    internal suspend fun emitByIds(
        database: AppDatabase,
        ids: List<String>,
        awaitRoom: suspend () -> Unit = {},
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ): Int {
        var emitted = 0
        val dao = database.eventDao()
        val maxSize = Settings.maxEventSize
        for (page in ids.chunked(QUERY_BATCH_SIZE)) {
            val lengths = HashMap<String, Int>(page.size * 2)
            dao.contentLengths(page).forEach { lengths[it.id] = it.length }
            val present = page.filter { id -> lengths[id]?.let { maxSize <= 0 || it <= maxSize } == true }

            var start = 0
            while (start < present.size) {
                currentCoroutineContext().ensureActive()

                val end = chunkEnd(start, present.size, BATCH_CONTENT_BUDGET_CHARS) { lengths[present[it]] ?: 0 }
                val chunk = present.subList(start, end)
                awaitRoom()
                // shortcut: a chunk bigger than the room left in the client's queue still waits on it mid-send while holding the permit; make awaitRoom size-aware if permits starve.
                historyChunkPermits.withPermit {
                    val byId = dao.getEventRowsByIds(chunk).associateBy { it.id }
                    emitWithTags(database, chunk.mapNotNull { byId[it] }) { batch ->
                        onBatch(batch)
                        emitted += batch.size
                    }
                }
                start = end
            }
        }
        return emitted
    }

    /**
     * Attaches tags to [rows] and hands them to [onBatch] in order, in chunks holding at
     * most [CHUNK_TAG_BUDGET] tags (a single event above the budget gets a chunk of its
     * own).
     *
     * Loading the tags of a whole page at once is what used to exhaust the heap: 500
     * contact lists are ~275k tag rows (~70 MB of [com.greenart7c3.citrine.database.TagEntity]
     * objects), a client's subscriptions run concurrently, and the load could not be
     * cancelled once started, so a few such REQs — or one client reconnecting and
     * re-issuing them — ended in an OutOfMemoryError.
     */
    internal suspend fun emitWithTags(
        database: AppDatabase,
        rows: List<EventEntity>,
        maxTagsPerEvent: Int = MAX_EVENT_TAGS,
        maxTagCharsPerEvent: Int = MAX_EVENT_TAG_CHARS,
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ) {
        val dao = database.eventDao()
        for (page in rows.chunked(QUERY_BATCH_SIZE)) {
            val tagCounts = HashMap<String, Int>(page.size * 2)
            dao.countTagsByEvent(page.map { it.id }).forEach { tagCounts[it.pkEvent] = it.count }

            // Events above the chunk budget may carry hundreds of thousands of duplicate tag
            // rows from Citrine 3.0.0–3.2.1; repair those in SQLite before loading, and skip
            // any event that still exceeds the hard per-event ceiling.
            val usable = ArrayList<EventEntity>(page.size)
            for (row in page) {
                var count = tagCounts[row.id] ?: 0
                if (count > CHUNK_TAG_BUDGET) {
                    currentCoroutineContext().ensureActive()
                    val removed = TagDeduplicator.deduplicateEvent(dao, row.id)
                    if (removed > 0) {
                        count = dao.countTagsForEvent(row.id)
                        tagCounts[row.id] = count
                    }
                    if (count > maxTagsPerEvent) {
                        Log.w(Citrine.TAG, "Skipping event ${row.id} (kind ${row.kind}) with $count tags (exceeds $maxTagsPerEvent)")
                        continue
                    }
                }
                usable.add(row)
            }

            var start = 0
            while (start < usable.size) {
                currentCoroutineContext().ensureActive()

                val end = chunkEnd(start, usable.size, CHUNK_TAG_BUDGET) { tagCounts[usable[it].id] ?: 0 }
                emitTagChunk(
                    database = database,
                    chunk = usable.subList(start, end),
                    tagCounts = tagCounts,
                    maxTagsPerEvent = maxTagsPerEvent,
                    maxTagCharsPerEvent = maxTagCharsPerEvent,
                    onBatch = onBatch,
                )
                start = end
            }
        }
    }

    // Separate frame so the chunk's TagEntity lists and EventWithTags batch are out of scope
    // and eligible for GC before the next chunk's query starts.
    private suspend fun emitTagChunk(
        database: AppDatabase,
        chunk: List<EventEntity>,
        tagCounts: Map<String, Int>,
        maxTagsPerEvent: Int,
        maxTagCharsPerEvent: Int,
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ) {
        val chunkTags = chunk.sumOf { tagCounts[it.id] ?: 0 }
        val tagResult = if (chunkTags == 0) {
            TagLoadResult(emptyMap(), emptySet())
        } else {
            loadTagsByEvents(database, chunk, maxTagsPerEvent, maxTagCharsPerEvent)
        }
        val events = ArrayList<EventWithTags>(chunk.size)
        for (row in chunk) {
            if (row.id in tagResult.oversizedEventIds) continue
            val tags = tagResult.tagsByEvent[row.id]
            // Tags were counted but are gone: the event was deleted or replaced
            // between the two reads. Sending it without tags would hand the client
            // an event whose id and signature no longer verify.
            if (tags == null && (tagCounts[row.id] ?: 0) > 0) continue
            events.add(EventWithTags(row, tags ?: emptyList()))
        }
        if (events.isNotEmpty()) onBatch(events)
    }

    internal data class TagLoadResult(
        val tagsByEvent: Map<String, List<TagEntity>>,
        val oversizedEventIds: Set<String>,
    )

    /**
     * Streams tag rows for [chunk] straight into a per-event map, skipping the intermediate
     * flat `List<TagEntity>` + `.groupBy` copy and `Long.valueOf(pk)` boxing, canonicalizing
     * `pkEvent` strings to the ids we already hold, checking coroutine cancellation mid-cursor,
     * and dropping any event whose tag count or text payload exceeds the per-event ceiling.
     */
    private suspend fun loadTagsByEvents(
        database: AppDatabase,
        chunk: List<EventEntity>,
        maxTagsPerEvent: Int,
        maxTagCharsPerEvent: Int,
    ): TagLoadResult {
        if (chunk.isEmpty()) return TagLoadResult(emptyMap(), emptySet())

        val canonicalIds = HashMap<String, String>(chunk.size * 2)
        val args = Array<Any>(chunk.size) { i ->
            val id = chunk[i].id
            canonicalIds[id] = id
            id
        }
        val sql = buildString(108 + chunk.size * 2) {
            append("SELECT pkEvent, position, col0Name, col1Value, col2Differentiator, col3Amount, col4Plus, kind FROM TagEntity WHERE pkEvent IN (")
            for (i in chunk.indices) {
                if (i > 0) append(',')
                append('?')
            }
            append(')')
        }

        val reader = TagRowReader(canonicalIds, chunk.size, maxTagsPerEvent, maxTagCharsPerEvent)
        var rowsRead = 0

        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            while (cursor.moveToNext()) {
                if ((rowsRead++ and 255) == 0) {
                    currentCoroutineContext().ensureActive()
                }
                reader.read(cursor)
            }
        }
        return TagLoadResult(reader.tagsByEvent, reader.oversized)
    }

    private class TagRowReader(
        private val canonicalIds: Map<String, String>,
        chunkSize: Int,
        private val maxTagsPerEvent: Int,
        private val maxTagCharsPerEvent: Int,
    ) {
        val tagsByEvent = HashMap<String, ArrayList<TagEntity>>(chunkSize * 2)
        private val charCounts = HashMap<String, Long>(chunkSize * 2)
        val oversized = HashSet<String>()

        fun read(cursor: Cursor) {
            val pkEvent = pkEventOf(cursor) ?: return

            val list = tagsByEvent.getOrPut(pkEvent) { ArrayList() }
            if (list.size >= maxTagsPerEvent) {
                oversized.add(pkEvent)
                tagsByEvent.remove(pkEvent)
                Log.w(Citrine.TAG, "Skipping event $pkEvent: tag count exceeded $maxTagsPerEvent while loading")
                return
            }

            tagOf(cursor, pkEvent)?.let { list.add(it) }
        }

        private fun pkEventOf(cursor: Cursor): String? {
            if (cursor.isNull(0)) return null
            val rawPkEvent = cursor.getString(0)
            val pkEvent = canonicalIds[rawPkEvent] ?: rawPkEvent
            return pkEvent.takeIf { it !in oversized }
        }

        private fun tagOf(cursor: Cursor, pkEvent: String): TagEntity? {
            val col0 = if (cursor.isNull(2)) null else internTagName(cursor.getString(2))
            val col1 = if (cursor.isNull(3)) null else cursor.getString(3)
            val col2 = if (cursor.isNull(4)) null else cursor.getString(4)
            val col3 = if (cursor.isNull(5)) null else cursor.getString(5)
            val col4Raw = if (cursor.isNull(6)) "" else cursor.getString(6)

            val rowChars = (col0?.length ?: 0).toLong() +
                (col1?.length ?: 0) +
                (col2?.length ?: 0) +
                (col3?.length ?: 0) +
                col4Raw.length
            val totalChars = (charCounts[pkEvent] ?: 0L) + rowChars
            charCounts[pkEvent] = totalChars
            if (totalChars > maxTagCharsPerEvent) {
                oversized.add(pkEvent)
                tagsByEvent.remove(pkEvent)
                Log.w(Citrine.TAG, "Skipping event $pkEvent: tag payload exceeded $maxTagCharsPerEvent chars")
                return null
            }

            val col4Plus: List<String> = if (col4Raw.isEmpty()) {
                emptyList()
            } else {
                JacksonMapper.mapper.readValue(col4Raw)
            }
            return TagEntity(
                pk = null,
                pkEvent = pkEvent,
                position = cursor.getInt(1),
                col0Name = col0,
                col1Value = col1,
                col2Differentiator = col2,
                col3Amount = col3,
                col4Plus = col4Plus,
                kind = cursor.getInt(7),
            )
        }
    }

    private fun internTagName(name: String): String = when (name) {
        "p" -> "p"
        "e" -> "e"
        "a" -> "a"
        "t" -> "t"
        "d" -> "d"
        "r" -> "r"
        "h" -> "h"
        "q" -> "q"
        "x" -> "x"
        "k" -> "k"
        else -> name
    }

    /**
     * End (exclusive) of the chunk starting at [start]: as many consecutive events as fit
     * in [budget] (tags or content chars, per [weight]), and always at least one, so an event
     * above the budget is not stuck.
     */
    internal inline fun chunkEnd(start: Int, size: Int, budget: Int, weight: (Int) -> Int): Int {
        var end = start
        var total = 0L
        while (end < size) {
            val w = weight(end)
            if (end > start && total + w > budget) break
            total += w
            end++
        }
        return end
    }

    // Subscriptions that are not registered in EventSubscription (or were evicted from it)
    // are not cancelled when their connection goes away, so the streaming loops check.
    private fun stopIfClosed(connection: Connection) {
        if (connection.closed.get()) throw CancellationException("connection ${connection.name} closed")
    }

    /** Same streaming loop as [batchedQuery] for id+createdAt-only rows, without a client limit. */
    suspend fun batchedIdsAndCreatedAt(
        database: AppDatabase,
        filter: EventFilter,
        onBatch: (List<IdAndCreatedAt>) -> Unit,
    ) {
        var cursorCreatedAt: Long? = null
        var cursorId: String? = null
        while (true) {
            val query = createQuery(filter, SelectMode.IDS_AND_CREATED_AT, cursorCreatedAt, cursorId, QUERY_BATCH_SIZE)
            val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
            val batch = database.eventDao().getIdsAndCreatedAt(rawSql)
            if (batch.isEmpty()) break

            onBatch(batch)
            if (batch.size < QUERY_BATCH_SIZE) break

            cursorCreatedAt = batch.last().createdAt
            cursorId = batch.last().id
        }
    }

    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        // NIP-29 private groups: events the connection may not read are filtered per
        // event, because a filter (e.g. {"kinds":[9]}) can match group content without
        // ever naming the group id. The per-event check works on the raw rows, so the
        // no-quartz-Event fast path below is preserved, and it only runs at all while a
        // private/hidden managed group exists.
        val applyNip29Gate = GroupManager.hasPrivateGroups()

        val sent = if (filter.searchKeywords.isNotEmpty()) {
            // FTS results are ordered by rowid, so the (createdAt, id) keyset cursor does
            // not apply and the matching ids are loaded in one shot; content and tags are
            // still loaded in bounded chunks.
            // shortcut: a common search term with no limit holds every matching id (~200 B each), page by rowid if that shows up.
            val query = createQuery(filter, SelectMode.IDS_AND_CREATED_AT)
            val ids = subscription.appDatabase.eventDao()
                .getIdsAndCreatedAt(SimpleSQLiteQuery(query.first, query.second.toTypedArray()))
                .map { it.id }
            emitByIds(subscription.appDatabase, ids, awaitRoom = subscription.connection::awaitQueueRoom) { batch ->
                stopIfClosed(subscription.connection)
                for (dbEvent in batch) {
                    if (applyNip29Gate && !GroupManager.canRead(dbEvent, subscription.connection)) {
                        continue
                    }
                    subscription.connection.send(dbEvent.toEventFrame(subscription.escapedId))
                }
            }
        } else {
            batchedQuery(subscription.appDatabase, filter, filter.limit, awaitRoom = subscription.connection::awaitQueueRoom) { batch ->
                stopIfClosed(subscription.connection)
                for (dbEvent in batch) {
                    if (applyNip29Gate && !GroupManager.canRead(dbEvent, subscription.connection)) {
                        continue
                    }
                    subscription.connection.send(dbEvent.toEventFrame(subscription.escapedId))
                }
            }
        }
        if (sent > 0 && Log.isLoggable(Citrine.TAG, Log.DEBUG)) {
            Log.d(Citrine.TAG, "sent $sent events for subscription ${subscription.id} filter $filter")
        }
    }

    /**
     * Estimates the serialized JSON frame length in chars (envelope + event fields + tags +
     * content) so [toEventFrame] allocates a single appropriately-sized buffer instead of
     * doubling repeatedly on large tag lists (e.g. kind-3 contact lists).
     */
    internal fun EventWithTags.estimateFrameChars(escapedSubId: String): Int {
        // Fixed envelope + field names + id(64) + pubkey(64) + sig(128) + numbers ≈ 357 chars.
        var chars = 360L + escapedSubId.length + event.content.length
        for (tag in tags) {
            // "[", "]", and trailing ","
            chars += 3
            tag.col0Name?.let { chars += it.length + 3 }
            tag.col1Value?.let { chars += it.length + 3 }
            tag.col2Differentiator?.let { chars += it.length + 3 }
            tag.col3Amount?.let { chars += it.length + 3 }
            for (extra in tag.col4Plus) {
                chars += extra.length + 3
            }
        }
        return chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Serializes a stored event straight from its database row, skipping the construction
     * of a kind-specific quartz [Event] and an intermediate Jackson tree. Produces the same
     * JSON as `toEvent().toJsonObject()`: tags are flattened the same way as
     * [com.greenart7c3.citrine.database.toTags] (non-null col0..col3, then col4Plus).
     */
    internal fun EventWithTags.toEventFrame(escapedSubId: String): String {
        // The frame is written once, prefix and suffix included, into a single buffer.
        val writer = StringWriter(estimateFrameChars(escapedSubId))
        writer.write("[\"EVENT\",")
        writer.write(escapedSubId)
        writer.write(",")
        JacksonMapper.mapper.factory.createGenerator(writer).use { gen ->
            gen.writeStartObject()
            gen.writeStringField("id", event.id)
            gen.writeStringField("pubkey", event.pubkey)
            gen.writeNumberField("created_at", event.createdAt)
            gen.writeNumberField("kind", event.kind)
            gen.writeArrayFieldStart("tags")
            for (tag in tags) {
                gen.writeStartArray()
                tag.col0Name?.let { gen.writeString(it) }
                tag.col1Value?.let { gen.writeString(it) }
                tag.col2Differentiator?.let { gen.writeString(it) }
                tag.col3Amount?.let { gen.writeString(it) }
                tag.col4Plus.forEach { gen.writeString(it) }
                gen.writeEndArray()
            }
            gen.writeEndArray()
            gen.writeStringField("content", event.content)
            gen.writeStringField("sig", event.sig)
            gen.writeEndObject()
        }
        writer.write("]")
        return writer.toString()
    }
}

fun Event.toJsonObject(): JsonNode = JACKSON_NODE_FACTORY.objectNode().apply {
    put("id", id)
    put("pubkey", pubKey)
    put("created_at", createdAt)
    put("kind", kind)
    replace(
        "tags",
        JACKSON_NODE_FACTORY.arrayNode(tags.size).apply {
            tags.forEach { tag ->
                add(
                    JACKSON_NODE_FACTORY.arrayNode(tag.size).apply { tag.forEach { add(it) } },
                )
            }
        },
    )
    put("content", content)
    put("sig", sig)
}
