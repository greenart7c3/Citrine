package com.greenart7c3.citrine.server

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.EventMapper.Companion.mapper
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import io.ktor.websocket.send

object EventRepository {
    suspend fun query(
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
            whereClause.add(
                filter.ids.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.id = ?"
                },
            )
            params.addAll(filter.ids)
        }

        if (filter.authors.isNotEmpty()) {
            whereClause.add(
                filter.authors.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.pubkey = ?"
                },
            )
            params.addAll(filter.authors)
        }

        if (filter.searchKeywords.isNotEmpty()) {
            whereClause.add(
                filter.searchKeywords.joinToString(" AND ", prefix = "(", postfix = ")") { "LOWER(EventEntity.content) LIKE ?" },
            )
            params.addAll(filter.searchKeywords.map { "%$it%" })
        }

        if (filter.kinds.isNotEmpty()) {
            whereClause.add(
                filter.kinds.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.kind = ?"
                },
            )
            params.addAll(filter.kinds)
        }

        if (filter.tags.isNotEmpty()) {
            val tags = filter.tags.filterValues { it.isNotEmpty() }
            var tagQuery = ""
            tags.forEach { tag ->
                tagQuery = "EXISTS (SELECT TagEntity.pkEvent FROM TagEntity WHERE EventEntity.id = TagEntity.pkEvent"
                tagQuery += " AND TagEntity.col0Name = ? AND ("
                params.add(tag.key)

                var count2 = 0
                tag.value.forEach {
                    if (count2 == 0) {
                        count2++
                    } else {
                        tagQuery += " OR "
                    }
                    tagQuery += " TagEntity.col1Value = ?"
                    params.add(it)
                }
                tagQuery += "))"

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

    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        val events = query(subscription.appDatabase, filter)

        if (subscription.count) {
            subscription.connection?.session?.send(
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
                subscription.connection?.session?.send(
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
    val factory = mapper.nodeFactory

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
