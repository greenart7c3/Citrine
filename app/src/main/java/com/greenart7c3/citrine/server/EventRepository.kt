package com.greenart7c3.citrine.server

import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventEntity
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.IdAndCreatedAt
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

private val TAG_KEY_REGEX = Regex("^[a-zA-Z0-9]+$")
private val JACKSON_NODE_FACTORY = JacksonMapper.mapper.nodeFactory

object EventRepository {
    private const val QUERY_BATCH_SIZE = 500
    private const val MIN_BATCH_SIZE = 10
    private const val BATCH_CONTENT_BUDGET_CHARS = 8L * 1024 * 1024

    // Tags held in memory at once per in-flight query (~3 MB of TagEntity objects).
    private const val CHUNK_TAG_BUDGET = 10_000

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

        // --- EventEntity filters ---
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

        // --- Add each tag as an AND EXISTS in WHERE ---
        // Note: no JOIN against TagEntity. The EXISTS predicates below do all the tag
        // filtering; a join would multiply each event row by its tag count and force a
        // DISTINCT over full-width rows (including content) to dedupe them again, which
        // is dramatically slower.
        filter.tags.forEach { tag ->
            val safeTagKey = tag.key.takeIf { it.matches(TAG_KEY_REGEX) }
                ?: throw IllegalArgumentException("Invalid tag key: ${tag.key}")

            val existsClause = StringBuilder()
                .append("EXISTS (SELECT 1 FROM TagEntity WHERE pkEvent = EventEntity.id AND col0Name = ?")
            params.add(safeTagKey)

            if (tag.value.isNotEmpty()) {
                val valuePlaceholders = tag.value.joinToString(",") { "?" }
                existsClause.append(" AND col1Value IN (").append(valuePlaceholders).append(")")
                params.addAll(tag.value)
            }

            if (filter.kinds.isNotEmpty()) {
                val kindPlaceholders = filter.kinds.joinToString(",") { "?" }
                existsClause.append(" AND kind IN (").append(kindPlaceholders).append(")")
                params.addAll(filter.kinds)
            }

            existsClause.append(")")

            appendWhere(existsClause)
        }

        // Never load rows whose content exceeds the size cap: a single multi-MB row can
        // exhaust the heap while being serialized (issue #187).
        if (mode == SelectMode.FULL_EVENTS && Settings.maxEventSize > 0) {
            appendWhere("length(EventEntity.content) <= ?")
            params.add(Settings.maxEventSize)
        }

        // Keyset pagination cursor: continues strictly after the previous page's last row
        // under the non-search ordering "createdAt DESC, id ASC", index-backed by
        // idx_event_created_id. Never combine with searchKeywords (rowid ordering).
        if (cursorCreatedAt != null && cursorId != null) {
            appendWhere("(EventEntity.createdAt < ? OR (EventEntity.createdAt = ? AND EventEntity.id > ?))")
            params.add(cursorCreatedAt)
            params.add(cursorCreatedAt)
            params.add(cursorId)
        }

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

        if (batchLimit != null) {
            query.append(" LIMIT ?")
            params.add(batchLimit)
        } else {
            filter.limit?.let {
                query.append(" LIMIT ?")
                params.add(it)
            }
        }
        return Pair(query.toString(), params)
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
     * smaller), so the whole match set is never materialized in the heap. Each page is
     * handed over in chunks of at most [CHUNK_TAG_BUDGET] tags (see [emitWithTags]).
     * Returns the total number of rows read.
     */
    suspend fun batchedQuery(
        database: AppDatabase,
        filter: EventFilter,
        clientLimit: Int?,
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ): Int {
        var sent = 0
        var cursorCreatedAt: Long? = null
        var cursorId: String? = null
        var batchSize = QUERY_BATCH_SIZE
        while (true) {
            // The queries below block uninterruptibly, so this is where a subscription
            // whose client sent CLOSE or disconnected stops costing memory and I/O.
            currentCoroutineContext().ensureActive()

            val effective = minOf(batchSize, clientLimit?.let { it - sent } ?: Int.MAX_VALUE)
            if (effective <= 0) break

            val query = createQuery(filter, SelectMode.FULL_EVENTS, cursorCreatedAt, cursorId, effective)
            val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
            val rows = database.eventDao().getEventRows(rawSql)
            if (rows.isEmpty()) break

            // Byte-aware paging: shrink the next page after a heavy one, recover after a light one.
            val chars = rows.sumOf { it.content.length.toLong() }
            batchSize = if (chars > BATCH_CONTENT_BUDGET_CHARS) {
                maxOf(MIN_BATCH_SIZE, batchSize / 2)
            } else {
                minOf(QUERY_BATCH_SIZE, batchSize * 2)
            }

            emitWithTags(database, rows, onBatch)
            sent += rows.size
            if (rows.size < effective) break

            cursorCreatedAt = rows.last().createdAt
            cursorId = rows.last().id
        }
        return sent
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
    private suspend fun emitWithTags(
        database: AppDatabase,
        rows: List<EventEntity>,
        onBatch: suspend (List<EventWithTags>) -> Unit,
    ) {
        val dao = database.eventDao()
        for (page in rows.chunked(QUERY_BATCH_SIZE)) {
            val tagCounts = HashMap<String, Int>(page.size * 2)
            dao.countTagsByEvent(page.map { it.id }).forEach { tagCounts[it.pkEvent] = it.count }

            var start = 0
            while (start < page.size) {
                currentCoroutineContext().ensureActive()

                val end = tagChunkEnd(start, page.size, CHUNK_TAG_BUDGET) { tagCounts[page[it].id] ?: 0 }
                val chunk = page.subList(start, end)
                val chunkTags = chunk.sumOf { tagCounts[it.id] ?: 0 }
                val tagsByEvent = if (chunkTags == 0) {
                    emptyMap()
                } else {
                    dao.getTagsByEvents(chunk.map { it.id }).groupBy { it.pkEvent }
                }
                val events = ArrayList<EventWithTags>(chunk.size)
                for (row in chunk) {
                    val tags = tagsByEvent[row.id]
                    // Tags were counted but are gone: the event was deleted or replaced
                    // between the two reads. Sending it without tags would hand the client
                    // an event whose id and signature no longer verify.
                    if (tags == null && (tagCounts[row.id] ?: 0) > 0) continue
                    events.add(EventWithTags(row, tags ?: emptyList()))
                }
                if (events.isNotEmpty()) onBatch(events)
                start = end
            }
        }
    }

    /**
     * End (exclusive) of the chunk starting at [start]: as many consecutive events as fit
     * in [budget] tags, and always at least one, so an event above the budget is not stuck.
     */
    internal inline fun tagChunkEnd(start: Int, size: Int, budget: Int, tagCount: (Int) -> Int): Int {
        var end = start
        var tags = 0L
        while (end < size) {
            val count = tagCount(end)
            if (end > start && tags + count > budget) break
            tags += count
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
            // not apply and the matching rows are loaded in one shot; FTS matches are
            // text-bounded, so that stays small. Their tags are still loaded in chunks.
            val query = createQuery(filter, SelectMode.FULL_EVENTS)
            val rows = subscription.appDatabase.eventDao()
                .getEventRows(SimpleSQLiteQuery(query.first, query.second.toTypedArray()))
            emitWithTags(subscription.appDatabase, rows) { batch ->
                stopIfClosed(subscription.connection)
                for (dbEvent in batch) {
                    if (applyNip29Gate && !GroupManager.canRead(dbEvent, subscription.connection)) {
                        continue
                    }
                    subscription.connection.send(dbEvent.toEventFrame(subscription.escapedId))
                }
            }
            rows.size
        } else {
            batchedQuery(subscription.appDatabase, filter, filter.limit) { batch ->
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
     * Serializes a stored event straight from its database row, skipping the construction
     * of a kind-specific quartz [Event] and an intermediate Jackson tree. Produces the same
     * JSON as `toEvent().toJsonObject()`: tags are flattened the same way as
     * [com.greenart7c3.citrine.database.toTags] (non-null col0..col3, then col4Plus).
     */
    private fun EventWithTags.toEventFrame(escapedSubId: String): String {
        // The frame is written once, prefix and suffix included, into a single buffer.
        val writer = StringWriter(256 + escapedSubId.length + event.content.length)
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
