package com.greenart7c3.citrine.server

import android.util.Log
import android.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.utils.isEphemeral
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

data class Subscription(
    val id: String,
    val connection: Connection,
    val filters: Set<EventFilter>,
    val appDatabase: AppDatabase,
    val objectMapper: ObjectMapper,
    val count: Boolean,
) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(500)

    fun getConnection(session: WebSocketServerSession): Connection? = subscriptions.snapshot().values.firstOrNull { it.subscription.connection.session == session }?.subscription?.connection

    fun count(): Int = subscriptions.size()

    fun executeAll(dbEvent: EventWithTags, connection: Connection?) {
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            val event = dbEvent.toEvent()
            val eventJson = event.toJsonObject()
            var sentEvent = false
            subscriptions.snapshot().values.forEach {
                it.subscription.filters.forEach filter@{ filter ->
                    if (filter.test(event)) {
                        it.subscription.connection.trySend(
                            it.subscription.objectMapper.writeValueAsString(
                                listOf(
                                    "EVENT",
                                    it.subscription.id,
                                    eventJson,
                                ),
                            ),
                        )

                        sentEvent = true
                    }
                }
            }
            if (event.isEphemeral()) {
                if (!sentEvent) {
                    connection?.trySend(
                        CommandResult.mute(event).toJson(),
                    )
                } else {
                    connection?.trySend(CommandResult.ok(event).toJson())
                }
            }
        }
    }

    fun closeAll(connectionName: String) {
        Log.d(Citrine.TAG, "finalizing subscriptions from $connectionName")
        subscriptions.snapshot().entries.forEach { (key, manager) ->
            if (manager.subscription.connection.name == connectionName) {
                Log.d(Citrine.TAG, "closing subscription $key")
                manager.subscription.scope.coroutineContext.cancelChildren()
                subscriptions.remove(key)
            }
        }
    }

    fun closeAll() {
        subscriptions.snapshot().keys.forEach {
            close(it)
        }
    }

    fun close(subscriptionId: String) {
        subscriptions.get(subscriptionId)?.subscription?.scope?.coroutineContext?.cancelChildren()
        subscriptions.remove(subscriptionId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun containsConnection(connection: Connection): Boolean = subscriptions.snapshot().values.any { it.subscription.connection.name == connection.name && !it.subscription.connection.session.outgoing.isClosedForSend }

    suspend fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        connection: Connection,
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
    }
}
