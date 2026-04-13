package com.greenart7c3.citrine.server

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.utils.isEphemeral
import io.ktor.server.websocket.WebSocketServerSession
import java.util.concurrent.ConcurrentHashMap
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
    private val subscriptions = ConcurrentHashMap<String, SubscriptionManager>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun getConnection(session: WebSocketServerSession): Connection? = subscriptions.values.firstOrNull { it.subscription.connection.session == session }?.subscription?.connection

    fun count(): Int = subscriptions.size

    fun executeAll(dbEvent: EventWithTags, connection: Connection?) {
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            val event = dbEvent.toEvent()
            val eventJsonStr = objectMapper.writeValueAsString(event.toJsonObject())
            var sentEvent = false
            subscriptions.values.forEach { manager ->
                val sub = manager.subscription
                if (sub.filters.any { filter -> filter.test(event) }) {
                    val subIdJson = objectMapper.writeValueAsString(sub.id)
                    sub.connection.trySend("""["EVENT",$subIdJson,$eventJsonStr]""")
                    sentEvent = true
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
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.subscription.connection.name == connectionName) {
                Log.d(Citrine.TAG, "closing subscription ${entry.key}")
                entry.value.subscription.scope.coroutineContext.cancelChildren()
                iterator.remove()
            }
        }
    }

    fun closeAll() {
        subscriptions.keys.forEach {
            close(it)
        }
    }

    fun close(subscriptionId: String) {
        subscriptions.remove(subscriptionId)?.subscription?.scope?.coroutineContext?.cancelChildren()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun containsConnection(connection: Connection): Boolean = subscriptions.values.any { it.subscription.connection.name == connection.name && !it.subscription.connection.session.outgoing.isClosedForSend }

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
        subscriptions[subscriptionId] = manager
        manager.execute()
    }
}
