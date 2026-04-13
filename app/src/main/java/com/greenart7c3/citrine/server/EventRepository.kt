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

                // Build the EVENT message bytes directly — avoids String→ByteArray re-encoding.
                val tb = System.nanoTime()
                val bytes = buildEventMessageBytes(subscription.id, eventEntity, tags)
                val ts = System.nanoTime()
                // Frame.Text(true, byteArray) uses the bytes as-is with no further encoding.
                val frame = Frame.Text(true, bytes)
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

// Pre-computed UTF-8 bytes for invariant JSON structural fragments.
// System.arraycopy from these constants is faster than encoding literal strings per-event.
private val J_EVENT_OPEN = "[\"EVENT\",".encodeToByteArray()
private val J_ID_OPEN = ",{\"id\":\"".encodeToByteArray()
private val J_PUBKEY_OPEN = "\",\"pubkey\":\"".encodeToByteArray()
private val J_CREATED_AT_OPEN = "\",\"created_at\":".encodeToByteArray()
private val J_KIND_OPEN = ",\"kind\":".encodeToByteArray()
private val J_TAGS_OPEN = ",\"tags\":[".encodeToByteArray()
private val J_CONTENT_OPEN = "],\"content\":".encodeToByteArray()
private val J_SIG_OPEN = ",\"sig\":\"".encodeToByteArray()
private val J_EVENT_CLOSE = "\"}]".encodeToByteArray()

/**
 * Builds ["EVENT","<subId>",{event}] directly as a UTF-8 [ByteArray].
 *
 * Writing bytes once (instead of writing chars to a StringBuilder then re-encoding to bytes
 * inside Frame.Text(String)) eliminates a full second O(N) pass over every character.
 * Structural JSON fragments are pre-computed constants copied via System.arraycopy.
 */
private fun buildEventMessageBytes(subId: String, entity: EventEntity, tags: List<TagEntity>): ByteArray {
    val estSize = 120 + entity.id.length + entity.pubkey.length + entity.sig.length +
        entity.content.length + tags.sumOf { 6 + (it.col0Name?.length ?: 0) + (it.col1Value?.length ?: 0) }
    val w = ByteWriter(estSize)
    w.writeRaw(J_EVENT_OPEN)
    w.writeJsonEncoded(subId)
    w.writeRaw(J_ID_OPEN)
    w.writeAscii(entity.id)
    w.writeRaw(J_PUBKEY_OPEN)
    w.writeAscii(entity.pubkey)
    w.writeRaw(J_CREATED_AT_OPEN)
    w.writeAscii(entity.createdAt.toString())
    w.writeRaw(J_KIND_OPEN)
    w.writeAscii(entity.kind.toString())
    w.writeRaw(J_TAGS_OPEN)
    tags.forEachIndexed { idx, tag ->
        if (idx > 0) w.writeByte(','.code)
        w.writeByte('['.code)
        var first = true
        fun part(s: String?) {
            if (s == null) return
            if (!first) w.writeByte(','.code)
            first = false
            w.writeJsonEncoded(s)
        }
        part(tag.col0Name)
        part(tag.col1Value)
        part(tag.col2Differentiator)
        part(tag.col3Amount)
        tag.col4Plus.forEach { part(it) }
        w.writeByte(']'.code)
    }
    w.writeRaw(J_CONTENT_OPEN)
    w.writeJsonEncoded(entity.content)
    w.writeRaw(J_SIG_OPEN)
    w.writeAscii(entity.sig)
    w.writeRaw(J_EVENT_CLOSE)
    return w.toByteArray()
}

/**
 * Minimal resizable byte buffer for building UTF-8 JSON without a String intermediary.
 *
 * Replacing StringBuilder+Frame.Text(String) with this writer eliminates one full O(N)
 * encoding pass: chars are encoded to bytes exactly once, and Frame.Text(true, byteArray)
 * uses the result directly with no further conversion.
 */
private class ByteWriter(initialCapacity: Int) {
    private var buf = ByteArray(initialCapacity)
    private var pos = 0

    private fun ensure(n: Int) {
        if (pos + n <= buf.size) return
        var cap = buf.size * 2
        while (cap < pos + n) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun writeByte(b: Int) {
        ensure(1)
        buf[pos++] = b.toByte()
    }

    /** Copies a pre-encoded [ByteArray] directly via [System.arraycopy] — no encoding overhead. */
    fun writeRaw(bytes: ByteArray) {
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buf, pos, bytes.size)
        pos += bytes.size
    }

    /**
     * Writes a pure-ASCII string byte-by-byte with zero allocation.
     * Used for hex IDs/pubkeys/sigs and numeric strings where every char is < 0x80.
     */
    fun writeAscii(s: String) {
        val len = s.length
        ensure(len)
        for (i in 0 until len) buf[pos++] = s[i].code.toByte()
    }

    /**
     * Writes [s] as a JSON string value (with surrounding quotes and proper escaping).
     *
     * Scans for characters that need escaping; flushes unescaped runs via [writeUtf8Run]
     * which uses the JDK's native UTF-8 encoder for bulk byte production.
     */
    fun writeJsonEncoded(s: String) {
        ensure(s.length + 2)
        buf[pos++] = '"'.code.toByte()
        var start = 0
        val len = s.length
        var i = 0
        while (i < len) {
            val ch = s[i]
            if (ch.code >= 0x20 && ch != '"' && ch != '\\') {
                i++
                continue
            }
            writeUtf8Run(s, start, i)
            ensure(6)
            when (ch) {
                '"' -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = '"'.code.toByte()
                }
                '\\' -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = '\\'.code.toByte()
                }
                '\n' -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = 'n'.code.toByte()
                }
                '\r' -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = 'r'.code.toByte()
                }
                '\t' -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = 't'.code.toByte()
                }
                else -> {
                    buf[pos++] = '\\'.code.toByte()
                    buf[pos++] = 'u'.code.toByte()
                    val hex = ch.code.toString(16).padStart(4, '0')
                    for (c in hex) buf[pos++] = c.code.toByte()
                }
            }
            start = i + 1
            i++
        }
        writeUtf8Run(s, start, len)
        ensure(1)
        buf[pos++] = '"'.code.toByte()
    }

    /**
     * Encodes [s][start..end) as UTF-8 with zero allocation.
     *
     * Reserves [3 × (end − start)] bytes up front (safe: surrogate pairs produce 4 bytes
     * across 2 chars = 2 bytes/char, unpaired surrogates produce 3 bytes/char).
     * Handles BMP chars and surrogate pairs; unpaired surrogates are encoded as-is (3 bytes).
     */
    private fun writeUtf8Run(s: String, start: Int, end: Int) {
        if (start >= end) return
        ensure((end - start) * 3)
        var i = start
        while (i < end) {
            val cp = s[i].code
            when {
                cp < 0x80 -> buf[pos++] = cp.toByte()
                cp < 0x800 -> {
                    buf[pos++] = (0xC0 or (cp ushr 6)).toByte()
                    buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
                }
                cp in 0xD800..0xDBFF && i + 1 < end -> {
                    val low = s[i + 1].code
                    if (low in 0xDC00..0xDFFF) {
                        val cp32 = 0x10000 + ((cp - 0xD800) shl 10) + (low - 0xDC00)
                        buf[pos++] = (0xF0 or (cp32 ushr 18)).toByte()
                        buf[pos++] = (0x80 or ((cp32 ushr 12) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or ((cp32 ushr 6) and 0x3F)).toByte()
                        buf[pos++] = (0x80 or (cp32 and 0x3F)).toByte()
                        i += 2
                        continue
                    }
                    buf[pos++] = (0xE0 or (cp ushr 12)).toByte()
                    buf[pos++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                    buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    buf[pos++] = (0xE0 or (cp ushr 12)).toByte()
                    buf[pos++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                    buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
                }
            }
            i++
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
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
