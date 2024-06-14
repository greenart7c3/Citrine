package com.greenart7c3.citrine.server

import androidx.sqlite.db.SimpleSQLiteQuery
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import io.ktor.websocket.send

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
            whereClause.add("EventEntity.kind IN (?)")
            params.add(filter.kinds.joinToString(","))
        }

        if (filter.tags.isNotEmpty()) {
            filter.tags.filterValues { it.isNotEmpty() }.forEach { tag ->
                whereClause.add(
                    "TagEntity.col0Name = ? AND TagEntity.col1Value in (?)",
                )
                params.add(tag.key)
                params.add(tag.value.map { "'$it'" }.toString().removePrefix("[").removeSuffix("]"))
            }
        }

        val predicatesSql = whereClause.joinToString(" AND ", prefix = "WHERE ")

        var query = """
            SELECT EventEntity.*
              FROM EventEntity EventEntity
              LEFT JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent
              $predicatesSql
              ORDER BY EventEntity.createdAt DESC
        """

        if (filter.limit > 0) {
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
            subscription.connection.session.send(
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
                subscription.connection.session.send(
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
