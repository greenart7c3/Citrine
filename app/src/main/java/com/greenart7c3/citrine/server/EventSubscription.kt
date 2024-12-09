package com.greenart7c3.citrine.server

import android.util.Log
import android.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Subscription(
    val id: String,
    val connection: Connection?,
    val filters: Set<EventFilter>,
    val appDatabase: AppDatabase,
    val objectMapper: ObjectMapper,
    val count: Boolean,
)

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(500)

    fun executeAll(dbEvent: EventWithTags, connection: Connection?) {
        Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            subscriptions.snapshot().values.forEach {
                it.subscription.filters.forEach filter@{ filter ->
                    val event = dbEvent.toEvent()
                    if (filter.test(event)) {
                        if (connection != null && it.subscription.connection?.name == connection.name) {
                            Log.d(Citrine.TAG, "skipping event to same connection")
                            return@filter
                        }

                        it.subscription.connection?.session?.send(
                            it.subscription.objectMapper.writeValueAsString(
                                listOf(
                                    "EVENT",
                                    it.subscription.id,
                                    event.toJsonObject(),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun closeAll(connectionName: String) {
        Log.d(Citrine.TAG, "finalizing subscriptions from $connectionName")
        subscriptions.snapshot().keys.forEach {
            if (subscriptions[it].subscription.connection?.name == connectionName) {
                Log.d(Citrine.TAG, "closing subscription $it")
                close(it)
            }
        }
    }

    fun closeAll() {
        subscriptions.snapshot().keys.forEach {
            close(it)
        }
    }

    fun close(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    suspend fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        connection: Connection?,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
        count: Boolean,
    ) {
        close(subscriptionId)
        val manager = SubscriptionManager(
            Subscription(
                subscriptionId,
                connection,
                filters,
                appDatabase,
                objectMapper,
                count,
            ),
        )
        subscriptions.put(
            subscriptionId,
            manager,
        )
        manager.execute()
        connection?.session?.send(EOSE(subscriptionId).toJson())
    }
}
