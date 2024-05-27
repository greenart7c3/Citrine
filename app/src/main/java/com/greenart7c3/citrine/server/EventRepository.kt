package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.database.toEvent
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking

object EventRepository {
    suspend fun subscribe(
        subscription: Subscription,
        filter: EventFilter,
    ) {
        var events = subscription.appDatabase.eventDao().getAll().sortedByDescending { it.event.createdAt }.toMutableList()

        if (filter.since != null) {
            events = events.filter { it.event.createdAt >= filter.since!! }.toMutableList()
        }

        if (filter.until != null) {
            events = events.filter { it.event.createdAt <= filter.until }.toMutableList()
        }

        if (filter.ids.isNotEmpty()) {
            events.removeAll { it.event.id !in filter.ids }
        }

        if (filter.authors.isNotEmpty()) {
            events.removeAll { it.event.pubkey !in filter.authors }
        }

        if (filter.searchKeywords.isNotEmpty()) {
            events.removeAll { event ->
                !filter.searchKeywords.any { keyword ->
                    event.event.content.contains(keyword, ignoreCase = true)
                }
            }
        }

        if (filter.kinds.isNotEmpty()) {
            events.removeAll { it.event.kind !in filter.kinds }
        }

        if (filter.tags.isNotEmpty()) {
            events.removeAll {
                !filter.tags.all { tag ->
                    tag.value.any { value ->
                        it.tags.any { it.col0Name == tag.key && it.col1Value == value }
                    }
                }
            }
        }

        if (filter.limit > 0) {
            events = events.take(filter.limit).sortedByDescending { it.event.createdAt }.toMutableList()
        }

        if (subscription.count) {
            runBlocking {
                subscription.connection.session.send(
                    subscription.objectMapper.writeValueAsString(
                        listOf(
                            "COUNT",
                            subscription.id,
                            CountResult(events.size).toJson(),
                        ),
                    ),
                )
            }
            return
        }

        events.forEach {
            val event = it.toEvent()
            if (!event.isExpired()) {
                runBlocking {
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
}
