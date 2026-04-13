package com.greenart7c3.citrine.server

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventEntity
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.TagEntity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import io.ktor.websocket.Frame
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object EventRepository {
    // Limit concurrent DB access to avoid thundering-herd on the WAL reader connection pool.
    // Room's default WAL pool is 2 connections; without this semaphore, 100+ subscription
    // coroutines all block waiting for a connection, causing massive lock contention and
    // inflating per-query latency from ~5ms to ~180ms. Capping at availableProcessors()
    // (min 2, max 4) means at most that many threads fight for connections at once.
    private val queryPermits = Semaphore(
        permits = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    )

    fun createQuery(
        filter: EventFilter,
        count: Boolean,
    ): Pair<String, List<Any>> {
        val whereClause = mutableListOf<String>()
        val params = mutableListOf<Any>()
        val joinClause = StringBuilder()

        // --- EventEntity filters ---
        filter.since?.let {
            whereClause.add("EventEntity.createdAt >= ?")
            params.add(it)
        }

        filter.until?.let {
            whereClause.add("EventEntity.createdAt <= ?")
            params.add(it)
        }

        if (filter.ids.isNotEmpty()) {
            val placeholders = filter.ids.joinToString(",") { "?" }
            whereClause.add("EventEntity.id IN ($placeholders)")
            params.addAll(filter.ids)
        }

        if (filter.authors.isNotEmpty()) {
            val placeholders = filter.authors.joinToString(",") { "?" }
            whereClause.add("EventEntity.pubkey IN ($placeholders)")
            params.addAll(filter.authors)
        }

        if (filter.searchKeywords.isNotEmpty()) {
            joinClause.append("INNER JOIN event_fts ON event_fts.rowid = EventEntity.rowid")
            val searchParam = filter.searchKeywords.joinToString(" ") { "\"$it\"" }
            whereClause.add("event_fts MATCH ?")
            params.add(searchParam)
        }

        if (filter.kinds.isNotEmpty()) {
            val placeholders = filter.kinds.joinToString(",") { "?" }
            whereClause.add("EventEntity.kind IN ($placeholders)")
            params.addAll(filter.kinds)
        }

        // --- Add each tag as an AND EXISTS in WHERE ---
        // Note: there is intentionally no INNER JOIN on TagEntity here.
        // A JOIN would expand each event into N rows (one per tag) and require DISTINCT to
        // deduplicate — multiplying work by avg_tags_per_event for no benefit.
        // EXISTS subqueries handle the filtering directly on the EventEntity rows.
        filter.tags.forEach { tag ->
            val safeTagKey = tag.key.takeIf { it.matches(Regex("^[a-zA-Z0-9]+$")) }
                ?: throw IllegalArgumentException("Invalid tag key: ${tag.key}")

            val existsClause = buildString {
                append("EXISTS (SELECT 1 FROM TagEntity WHERE pkEvent = EventEntity.id AND col0Name = ?")
                params.add(safeTagKey)

                if (tag.value.isNotEmpty()) {
                    val valuePlaceholders = tag.value.joinToString(",") { "?" }
                    append(" AND col1Value IN ($valuePlaceholders)")
                    params.addAll(tag.value)
                }

                if (filter.kinds.isNotEmpty()) {
                    val kindPlaceholders = filter.kinds.joinToString(",") { "?" }
                    append(" AND kind IN ($kindPlaceholders)")
                    params.addAll(filter.kinds)
                }

                append(")")
            }

            whereClause.add(existsClause)
        }

        // --- Build SQL ---
        val joinSql = if (joinClause.isNotEmpty()) joinClause.toString() else ""
        val predicatesSql = if (whereClause.isNotEmpty()) whereClause.joinToString(" AND ", prefix = "WHERE ") else ""

        val orderBy = if (filter.searchKeywords.isNotEmpty()) {
            "EventEntity.rowid DESC"
        } else {
            "EventEntity.createdAt DESC, EventEntity.id ASC"
        }

        var query = if (count) {
            """
        SELECT COUNT(EventEntity.id)
          FROM EventEntity EventEntity
          $joinSql
          $predicatesSql
            """.trimIndent()
        } else {
            """
        SELECT EventEntity.*
          FROM EventEntity EventEntity
          $joinSql
          $predicatesSql
          ORDER BY $orderBy
            """.trimIndent()
        }

        filter.limit?.let {
            query += " LIMIT ?"
            params.add(it)
        }
        return Pair(query, params)
    }

    fun query(
        database: AppDatabase,
        filter: EventFilter,
    ): List<EventWithTags> {
        val query = createQuery(filter, false)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().getEvents(rawSql)
    }

    fun countQuery(
        database: AppDatabase,
        filter: EventFilter,
    ): Int {
        val query = createQuery(filter, true)

        val rawSql = SimpleSQLiteQuery(query.first, query.second.toTypedArray())
        return database.eventDao().count(rawSql)
    }

    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        // Acquire a permit before touching the DB so that at most queryPermits.availablePermits
        // coroutines block on the connection pool at once, eliminating thundering-herd contention.
        val t0 = System.nanoTime()
        queryPermits.withPermit {
            val t1 = System.nanoTime()
            if (subscription.count) {
                val count = countQuery(subscription.appDatabase, filter)
                subscription.connection.trySend(
                    subscription.objectMapper.writeValueAsString(
                        listOf(
                            "COUNT",
                            subscription.id,
                            CountResult(count).toJson(),
                        ),
                    ),
                )
                return@withPermit
            }

            val (sql, params) = createQuery(filter, false)
            val rawSql = SimpleSQLiteQuery(sql, params.toTypedArray())
            val eventEntities = subscription.appDatabase.eventDao().getEventsOnly(rawSql)
            val t2 = System.nanoTime()
            if (eventEntities.isEmpty()) return@withPermit

            // Batch-load all tags in one query instead of Room's @Relation N+1 pattern.
            // Chunk to stay under SQLite's 999-variable limit.
            val tagsByEvent = eventEntities
                .map { it.id }
                .chunked(500) { chunk ->
                    subscription.appDatabase.eventDao().getTagsForEvents(chunk)
                }
                .flatten()
                .groupBy { it.pkEvent }

            val t3 = System.nanoTime()
            val nowSeconds = System.currentTimeMillis() / 1000

            var buildNs = 0L
            var frameNs = 0L
            var sendNs = 0L
            eventEntities.forEach { eventEntity ->
                val tags = tagsByEvent[eventEntity.id] ?: emptyList()

                // Check NIP-40 expiration directly on tags — avoids allocating a full Event object
                val expiry = tags.firstOrNull { it.col0Name == "expiration" }?.col1Value?.toLongOrNull()
                if (expiry != null && expiry < nowSeconds) return@forEach

                // Build the EVENT message as a raw JSON string without Jackson intermediaries
                val tb = System.nanoTime()
                val msg = buildEventMessage(subscription.id, eventEntity, tags)
                val ts = System.nanoTime()
                // Pre-encode String→ByteArray once here; Frame.Text(String) would do this anyway.
                val frame = Frame.Text(msg)
                val tf = System.nanoTime()
                subscription.connection.trySend(frame)
                val te = System.nanoTime()
                buildNs += ts - tb
                frameNs += tf - ts
                sendNs += te - tf
            }
            val t4 = System.nanoTime()
            Log.d(
                Citrine.TAG,
                "subscribe phases: semWait=${(t1 - t0) / 1_000_000}ms " +
                    "query1=${(t2 - t1) / 1_000_000}ms " +
                    "tags=${(t3 - t2) / 1_000_000}ms " +
                    "send=${(t4 - t3) / 1_000_000}ms " +
                    "build=${buildNs / 1_000_000}ms frame=${frameNs / 1_000_000}ms trySend=${sendNs / 1_000_000}ms " +
                    "events=${eventEntities.size}",
            )
        }
    }
}

/** Builds ["EVENT","<subId>",{event}] without Jackson intermediate objects. */
private fun buildEventMessage(subId: String, entity: EventEntity, tags: List<TagEntity>): String = buildString(capacity = 1024) {
    append("[\"EVENT\",")
    appendJsonEncoded(subId)
    append(",{\"id\":\"")
    append(entity.id)
    append("\",\"pubkey\":\"")
    append(entity.pubkey)
    append("\",\"created_at\":")
    append(entity.createdAt)
    append(",\"kind\":")
    append(entity.kind)
    append(",\"tags\":[")
    tags.forEachIndexed { i, tag ->
        if (i > 0) append(',')
        append('[')
        appendTagParts(tag)
        append(']')
    }
    append("],\"content\":")
    appendJsonEncoded(entity.content)
    append(",\"sig\":\"")
    append(entity.sig)
    append("\"}]")
}

/** Appends the non-null tag parts as a comma-separated JSON string list. */
private fun StringBuilder.appendTagParts(tag: TagEntity) {
    var first = true
    fun part(s: String?) {
        if (s == null) return
        if (!first) append(',')
        first = false
        appendJsonEncoded(s)
    }
    part(tag.col0Name)
    part(tag.col1Value)
    part(tag.col2Differentiator)
    part(tag.col3Amount)
    tag.col4Plus.forEach { part(it) }
}

/**
 * Appends [s] as a JSON-encoded string (surrounding quotes + proper escaping).
 *
 * Uses a bulk-copy strategy: scan for characters that need escaping, and flush
 * the unescaped runs between them with a single [StringBuilder.append] call that
 * resolves to [System.arraycopy] internally — orders of magnitude faster than the
 * previous char-by-char append loop for typical content with few or no special chars.
 */
private fun StringBuilder.appendJsonEncoded(s: String) {
    append('"')
    var start = 0
    val len = s.length
    var i = 0
    while (i < len) {
        val ch = s[i]
        // Fast path: printable ASCII that isn't " or \ needs no escaping — keep scanning.
        if (ch.code >= 0x20 && ch != '"' && ch != '\\') {
            i++
            continue
        }
        // Flush the unescaped run [start, i) as a single bulk copy (System.arraycopy).
        if (i > start) append(s, start, i)
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        }
        start = i + 1
        i++
    }
    // Flush any remaining unescaped suffix.
    if (start < len) append(s, start, len)
    append('"')
}

fun Event.toJsonObject(): JsonNode {
    val factory = JacksonMapper.mapper.nodeFactory

    return factory.objectNode().apply {
        put("id", id)
        put("pubkey", pubKey)
        put("created_at", createdAt)
        put("kind", kind)
        replace(
            "tags",
            factory.arrayNode(tags.size).apply {
                tags.forEach { tag ->
                    add(
                        factory.arrayNode(tag.size).apply { tag.forEach { add(it) } },
                    )
                }
            },
        )
        put("content", content)
        put("sig", sig)
    }
}
