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
    private const val BATCH_CONTENT_BUDGET_CHARS = 1024 * 1024
    internal const val CHUNK_TAG_BUDGET = 10_000
    internal const val MAX_EVENT_TAGS = 50_000
    internal const val MAX_EVENT_TAG_CHARS = 8 * 1024 * 1024
    private const val HISTORY_CHUNK_PERMITS = 4
    private const val FRAME_ENVELOPE_CHARS = 360L
    private const val TAG_ARRAY_OVERHEAD_CHARS = 3
    private const val TAG_VALUE_OVERHEAD_CHARS = 3
    private val historyChunkPermits = Semaphore(HISTORY_CHUNK_PERMITS)

    enum class SelectMode {
        FULL_EVENTS,
        COUNT,
        IDS_AND_CREATED_AT,
    }

    /**
     * Builds [filter]'s SQL for [mode] and the parameters it binds, in order.
     *
     * Tag filters are `EXISTS` predicates, never a JOIN against TagEntity: a join would
     * multiply each event row by its tag count and force a DISTINCT over full-width rows
     * (content included) to dedupe them again, which is dramatically slower. The FTS join is
     * 1:1 (rowid = rowid), so no row is ever duplicated and no mode needs DISTINCT.
     *
     * [SelectMode.FULL_EVENTS] never loads a row whose content exceeds [Settings.maxEventSize]:
     * a single multi-MB row can exhaust the heap while being serialized
     * (https://github.com/greenart7c3/Citrine/issues/187).
     *
     * [cursorCreatedAt] and [cursorId] continue strictly after the previous page's last row
     * under the non-search ordering "createdAt DESC, id ASC", index-backed by
     * idx_event_created_id. Never combine them with search keywords, which order by rowid.
     * [batchLimit], when set, replaces the filter's limit.
     */
    fun createQuery(
        filter: EventFilter,
        mode: SelectMode,
        cursorCreatedAt: Long? = null,
        cursorId: String? = null,
        batchLimit: Int? = null,
    ): Pair<String, List<Any>> {
        val sql = QueryParts()

        sql.appendEventFilters(filter)

        filter.tags.forEach { tag -> sql.appendTagExists(tag.key, tag.value, filter.kinds) }

        if (mode == SelectMode.FULL_EVENTS && Settings.maxEventSize > 0) {
            sql.appendWhere("length(EventEntity.content) <= ?")
            sql.params.add(Settings.maxEventSize)
        }

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

        private fun appendNotExpiredUnderNip40() {
            appendWhere("(EventEntity.expiresAt IS NULL OR EventEntity.expiresAt >= ?)")
            params.add(TimeUtils.now())
        }

        fun appendEventFilters(filter: EventFilter) {
            appendNotExpiredUnderNip40()

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
            val orderBy = if (filter.searchKeywords.isNotEmpty()) {
                "EventEntity.rowid DESC"
            } else {
                "EventEntity.createdAt DESC, EventEntity.id ASC"
            }

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
     * smaller), so the whole match set is never materialized in memory. Returns the number of
     * events sent: events skipped as oversized, or deleted since their id was read, do not
     * count against [clientLimit].
     *
     * Each page is selected as ids only: with an IN list on authors/kinds SQLite sorts the
     * candidates in a temp B-tree (native memory, temp_store=MEMORY), and full rows would put
     * up to QUERY_BATCH_SIZE x maxEventSize of content there, on top of the same in the heap.
     * The page's events are then loaded in chunks of at most one max-size event's content and
     * [CHUNK_TAG_BUDGET] tags (an event above a budget gets a chunk of its own): loading a
     * whole page's tags at once (500 contact lists are ~275k tag rows, ~70 MB) used to end in
     * an OutOfMemoryError. Each chunk is loaded and handed over under one of a few app-wide
     * permits, whatever the number of connections and subscriptions (~40 MB in all).
     *
     * [awaitRoom] runs before a permit is taken: it should wait until the receiving client can
     * take the chunk, so a client that is slow or not reading does not hold a permit every
     * other connection needs. A chunk bigger than the room left in the client's queue still
     * waits on it mid-send while holding the permit.
     *
     * Cancellation is checked before every page and chunk because the queries block
     * uninterruptibly: that is where a subscription whose client sent CLOSE or disconnected
     * stops costing memory and I/O.
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
            currentCoroutineContext().ensureActive()

            val effective = minOf(QUERY_BATCH_SIZE, clientLimit?.let { it - sent } ?: Int.MAX_VALUE)
            if (effective <= 0) break

            val query = createQuery(filter, SelectMode.IDS_AND_CREATED_AT, cursorCreatedAt, cursorId, effective)
            val page = database.eventDao().getIdsAndCreatedAt(SimpleSQLiteQuery(query.first, query.second.toTypedArray()))
            if (page.isEmpty()) break

            sent += emitByIds(database, page.map { it.id }, awaitRoom, onBatch)
            if (page.size < effective) break

            cursorCreatedAt = page.last().createdAt
            cursorId = page.last().id
        }
        return sent
    }

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
    @Suppress("NoComments")
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
            // Sending it without tags would hand the client an event whose id and signature no longer verify.
            val deletedOrReplacedBetweenReads = tags == null && (tagCounts[row.id] ?: 0) > 0
            if (deletedOrReplacedBetweenReads) continue
            events.add(EventWithTags(row, tags ?: emptyList()))
        }
        if (events.isNotEmpty()) onBatch(events)
    }

    internal data class TagLoadResult(
        val tagsByEvent: Map<String, List<TagEntity>>,
        val oversizedEventIds: Set<String>,
    )

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

    /**
     * Sends [filter]'s stored matches to [subscription]'s connection as EVENT frames.
     *
     * NIP-29 private groups: events the connection may not read are filtered per event,
     * because a filter (e.g. {"kinds":[9]}) can match group content without ever naming the
     * group id. The per-event check works on the raw rows, so no quartz Event is built, and it
     * only runs at all while a private/hidden managed group exists.
     *
     * Search results are ordered by rowid, so the (createdAt, id) keyset cursor does not apply:
     * every matching id (~200 B each) is loaded in one shot, while content and tags are still
     * loaded in bounded chunks as in [batchedQuery].
     */
    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        val applyNip29Gate = GroupManager.hasPrivateGroups()

        val sent = if (filter.searchKeywords.isNotEmpty()) {
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

    internal fun EventWithTags.estimateFrameChars(escapedSubId: String): Int {
        var chars = FRAME_ENVELOPE_CHARS + escapedSubId.length + event.content.length
        for (tag in tags) {
            chars += TAG_ARRAY_OVERHEAD_CHARS
            tag.col0Name?.let { chars += it.length + TAG_VALUE_OVERHEAD_CHARS }
            tag.col1Value?.let { chars += it.length + TAG_VALUE_OVERHEAD_CHARS }
            tag.col2Differentiator?.let { chars += it.length + TAG_VALUE_OVERHEAD_CHARS }
            tag.col3Amount?.let { chars += it.length + TAG_VALUE_OVERHEAD_CHARS }
            for (extra in tag.col4Plus) {
                chars += extra.length + TAG_VALUE_OVERHEAD_CHARS
            }
        }
        return chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun EventWithTags.toEventFrame(escapedSubId: String): String {
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
