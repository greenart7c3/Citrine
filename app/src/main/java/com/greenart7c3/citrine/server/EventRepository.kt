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

object EventRepository {
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

        // --- Single JOIN (just for connecting EventEntity with TagEntity if needed) ---
        if (filter.tags.isNotEmpty()) {
            joinClause.append(" INNER JOIN TagEntity t ON t.pkEvent = EventEntity.id")
        }

        // --- Add each tag as an AND EXISTS in WHERE ---
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
        val distinctSql = if (filter.tags.isNotEmpty()) "DISTINCT " else ""

        val orderBy = if (filter.searchKeywords.isNotEmpty()) {
            "EventEntity.rowid DESC"
        } else {
            "EventEntity.createdAt DESC, EventEntity.id ASC"
        }

        var query = if (count) {
            """
        SELECT COUNT(DISTINCT EventEntity.id)
          FROM EventEntity EventEntity
          $joinSql
          $predicatesSql
            """.trimIndent()
        } else {
            """
        SELECT $distinctSql EventEntity.*
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
