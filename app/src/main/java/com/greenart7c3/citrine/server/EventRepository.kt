package com.greenart7c3.citrine.server

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString

private val TAG_KEY_REGEX = Regex("^[a-zA-Z0-9]+$")
private val JACKSON_NODE_FACTORY = JacksonMapper.mapper.nodeFactory

object EventRepository {
    fun createQuery(
        filter: EventFilter,
        count: Boolean,
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

        // --- Single JOIN (just for connecting EventEntity with TagEntity if needed) ---
        if (filter.tags.isNotEmpty()) {
            joinClause.append(" INNER JOIN TagEntity t ON t.pkEvent = EventEntity.id")
        }

        // --- Add each tag as an AND EXISTS in WHERE ---
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

        // --- Build SQL ---
        val orderBy = if (filter.searchKeywords.isNotEmpty()) {
            "EventEntity.rowid DESC"
        } else {
            "EventEntity.createdAt DESC, EventEntity.id ASC"
        }

        val query = StringBuilder()
        if (count) {
            query.append("SELECT COUNT(DISTINCT EventEntity.id) FROM EventEntity EventEntity")
            query.append(joinClause)
            query.append(whereClause)
        } else {
            query.append("SELECT ")
            if (filter.tags.isNotEmpty()) query.append("DISTINCT ")
            query.append("EventEntity.* FROM EventEntity EventEntity")
            query.append(joinClause)
            query.append(whereClause)
            query.append(" ORDER BY ").append(orderBy)
        }

        filter.limit?.let {
            query.append(" LIMIT ?")
            params.add(it)
        }
        return Pair(query.toString(), params)
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

    fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
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
            return
        }

        val events = query(subscription.appDatabase, filter)
        events.forEach {
            val event = it.toEvent()
            if (!event.isExpired()) {
                Log.d(Citrine.TAG, "sending event ${event.id} subscription ${subscription.id} filter $filter")
                subscription.connection.trySend(
                    subscription.objectMapper.writeValueAsString(
                        listOf(
                            "EVENT",
                            subscription.id,
                            event.toJsonObject(),
                        ),
                    ),
                )
            }
        }
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
