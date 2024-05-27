package com.greenart7c3.citrine.server

import android.util.Log
import android.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import io.ktor.websocket.send
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Subscription(
    val id: String,
    val connection: Connection,
    val filters: Set<EventFilter>,
    val appDatabase: AppDatabase,
    val objectMapper: ObjectMapper,
    val count: Boolean,
)

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(500)
    private val _subscriptionCount = MutableStateFlow(0)
    val subscriptionCount = _subscriptionCount.asStateFlow()

    @OptIn(DelicateCoroutinesApi::class)
    fun executeAll(dbEvent: EventWithTags) {
        Log.d("executeAll", "executeAll")
        GlobalScope.launch(Dispatchers.IO) {
            subscriptions.snapshot().values.forEach {
                it.subscription.filters.forEach { filter ->
                    val event = dbEvent.toEvent()
                    if (filter.test(event)) {
                        it.subscription.connection.session.send(
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
        Log.d("connection", "finalizing subscriptions from $connectionName")
        subscriptions.snapshot().keys.forEach {
            if (subscriptions[it].subscription.connection.name == connectionName) {
                Log.d("connection", "closing subscription $it")
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
        _subscriptionCount.value = subscriptions.size()
        Log.d("subscriptions", subscriptions.size().toString())
    }

    fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        connection: Connection,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
        count: Boolean,
    ) {
        Log.d("subscriptions", "new subscription $subscriptionId")
        close(subscriptionId)
        subscriptions.put(
            subscriptionId,
            SubscriptionManager(
                Subscription(
                    subscriptionId,
                    connection,
                    filters,
                    appDatabase,
                    objectMapper,
                    count,
                ),
            ),
        )
        _subscriptionCount.value = subscriptions.size()
    }
}
