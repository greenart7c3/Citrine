package com.greenart7c3.citrine

import com.greenart7c3.citrine.database.toEvent
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking

object EventRepository {
    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter
    ) {
        val whereClause = mutableListOf<String>()

        if (filter.since != null) {
            whereClause.add("EventEntity.createdAt >= ${filter.since}")
        }

        if (filter.until != null) {
            whereClause.add("EventEntity.createdAt <= ${filter.until}")
        }

        if (filter.ids.isNotEmpty()) {
            whereClause.add(
                filter.ids.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.id = '$it'"
                }
            )
        }

        if (filter.authors.isNotEmpty()) {
            whereClause.add(
                filter.authors.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.pubkey = '$it'"
                }
            )
        }

        if (filter.searchKeywords.isNotEmpty()) {
            whereClause.add(
                filter.searchKeywords.joinToString(" AND ", prefix = "(", postfix = ")") { "LOWER(EventEntity.content) LIKE '%$it%'" }
            )
        }

        if (filter.kinds.isNotEmpty()) {
            whereClause.add("EventEntity.kind IN (${filter.kinds.joinToString(",")})")
        }

        if (filter.tags.isNotEmpty()) {
            filter.tags.filterValues { it.isNotEmpty() }.forEach { tag ->
                whereClause.add(
                    "TagEntity.col0Name = '${tag.key}' AND TagEntity.col1Value = '${tag.value.toString().removePrefix("[").removeSuffix("]")}'"
                )
            }
        }

        val predicatesSql = whereClause.joinToString(" AND ", prefix = "WHERE ")

        var notInFilter = ""
        if (subscription.eventIdsSent.isNotEmpty()) {
            notInFilter = "AND EventEntity.id NOT IN ${subscription.eventIdsSent.joinToString(prefix = "(", postfix = ")") { "'$it'" }}"
        }

        var query = """
            SELECT EventEntity.id
              FROM EventEntity EventEntity
              LEFT JOIN TagEntity TagEntity ON EventEntity.id = TagEntity.pkEvent
              $predicatesSql 
              $notInFilter
              ORDER BY EventEntity.createdAt DESC
        """

        if (filter.limit > 0) {
            query += " LIMIT ${filter.limit}"
        }

        val cursor = subscription.appDatabase.query(query, arrayOf())
        cursor.use { item ->
            while (item.moveToNext()) {
                val eventEntity = subscription.appDatabase.eventDao().getById(item.getString(0))
                eventEntity?.let {
                    val event = it.toEvent()
                    if (!event.isExpired()) {
                        subscription.eventIdsSent = subscription.eventIdsSent.plus(event.id).toMutableList()
                        runBlocking {
                            subscription.connection.session.send(
                                subscription.objectMapper.writeValueAsString(
                                    listOf(
                                        "EVENT",
                                        subscription.id,
                                        event.toJsonObject()
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
