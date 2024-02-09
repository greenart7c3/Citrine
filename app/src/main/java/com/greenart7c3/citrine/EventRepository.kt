package com.greenart7c3.citrine

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking

object EventRepository {
    suspend fun subscribe(
        subscriptionId: String,
        filter: EventFilter,
        session: DefaultWebSocketServerSession,
        context: Context,
        objectMapper: ObjectMapper
    ) {
        val whereClause = mutableListOf<String>()
        val parameters = mutableListOf<String>()

        if (filter.since != null) {
            whereClause.add("EventEntity.createdAt >= ${filter.since}")
        }

        if (filter.until != null) {
            whereClause.add("EventEntity.createdAt <= ${filter.until}")
        }

        if (filter.ids.isNotEmpty()) {
            whereClause.add(
                filter.ids.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.id = '${it}'"
                }
            )
        }

        if (filter.authors.isNotEmpty()) {
            whereClause.add(
                filter.authors.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "EventEntity.pubkey = '${it}'"
                }
            )
        }

        if (filter.searchKeywords.isNotEmpty()) {
            whereClause.add(
                filter.searchKeywords.joinToString(" AND ", prefix = "(", postfix = ")") { "EventEntity.content ~* ?" }
            )

            parameters.addAll(filter.searchKeywords.map { it })
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

        var query = """
            SELECT EventEntity.pk
              FROM EventEntity EventEntity
              LEFT JOIN TagEntity TagEntity ON EventEntity.pk = TagEntity.pkEvent
              $predicatesSql 
              ORDER BY EventEntity.createdAt DESC
        """

        if (filter.limit > 0) {
            query += " LIMIT ${filter.limit}"
        }

        val cursor = AppDatabase.getDatabase(context).query(query, parameters.toTypedArray())
        cursor.use { item ->
            while (item.moveToNext()) {
                val eventEntity = AppDatabase.getDatabase(context).eventDao().getById(item.getString(0))
                eventEntity?.let {
                    runBlocking {
                        session.send(
                            objectMapper.writeValueAsString(
                                listOf("EVENT", subscriptionId, it.toEvent().toJsonObject())
                            ),
                        )
                    }
                }
            }
        }
    }
}