package com.greenart7c3.citrine.server

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.storage.EventStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip40Expiration.isExpired

object EventRepository {
    fun query(store: EventStore, filter: EventFilter): List<Event> = store.query(filter)

    fun countQuery(store: EventStore, filter: EventFilter): Int = store.count(filter)

    fun subscribe(subscription: Subscription, filter: EventFilter) {
        if (subscription.count) {
            val count = countQuery(subscription.eventStore, filter)
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

        val events = query(subscription.eventStore, filter)
        events.forEach { event ->
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
