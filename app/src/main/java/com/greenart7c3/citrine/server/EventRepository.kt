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
    fun query(
        database: AppDatabase,
        filter: EventFilter,
    ): List<EventWithTags> {
        val whereClause = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (filter.since != null) {
            whereClause.add("EventEntity.createdAt >= ?")
            params.add(filter.since!!)
        }

        if (filter.until != null) {
            whereClause.add("EventEntity.createdAt <= ?")
            params.add(filter.until)
        }

        if (filter.ids.isNotEmpty()) {
            whereClause.add("EventEntity.id IN ${filter.ids.map { "'$it'" }.toString().replace("[", "(").replace("]", ")")}")
        }

        if (filter.authors.isNotEmpty()) {
            whereClause.add("EventEntity.pubkey IN ${filter.authors.map { "'$it'" }.toString().replace("[", "(").replace("]", ")")}")
        }

        if (filter.searchKeywords.isNotEmpty()) {
            whereClause.add(
                filter.searchKeywords.joinToString(" AND ", prefix = "(", postfix = ")") { "LOWER(EventEntity.content) LIKE ?" },
            )
            params.addAll(filter.searchKeywords.map { "%$it%" })
        }

        if (filter.kinds.isNotEmpty()) {
            whereClause.add("EventEntity.kind IN ${filter.kinds.toString().replace("[", "(").replace("]", ")")}")
        }

        if (filter.tags.isNotEmpty()) {
            val tags = filter.tags.filterValues { it.isNotEmpty() }
            var tagQuery: String
            tags.forEach { tag ->
                tagQuery = "EventEntity.id IN (SELECT TagEntity.pkEvent FROM TagEntity WHERE 1=1"
                if (filter.kinds.isNotEmpty()) {
                    tagQuery += " AND TagEntity.kind IN ${filter.kinds.toString().replace("[", "(").replace("]", ")")}"
                }
                tagQuery += " AND TagEntity.col0Name = '${tag.key}'"
                tagQuery += " AND TagEntity.col1Value IN (${tag.value.map { "'${it.replace("'", "''")}'" }.toString().replace("[", "").replace("]", "")}))"
                whereClause.add(tagQuery)
            }
        }

        val predicatesSql = if (whereClause.isNotEmpty()) whereClause.joinToString(" AND ", prefix = "WHERE ") else ""

        var query = """
            SELECT EventEntity.*
              FROM EventEntity EventEntity
              $predicatesSql
              ORDER BY EventEntity.createdAt DESC, EventEntity.id ASC
        """

        if (filter.limit != null) {
            query += " LIMIT ?"
            params.add(filter.limit)
        }

        val rawSql = SimpleSQLiteQuery(query, params.toTypedArray())
        return database.eventDao().getEvents(rawSql)
    }

    fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        val events = query(subscription.appDatabase, filter)

        if (subscription.count) {
            subscription.connection.trySend(
                subscription.objectMapper.writeValueAsString(
                    listOf(
                        "COUNT",
                        subscription.id,
                        CountResult(events.size).toJson(),
                    ),
                ),
            )
            return
        }

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
