package com.greenart7c3.citrine.server

import android.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.json.JacksonMapper
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.utils.isEphemeral
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val ENVELOPE_MAPPER = JacksonMapper.mapper

data class Subscription(
    val id: String,
    val connection: Connection,
    val filters: Set<EventFilter>,
    val appDatabase: AppDatabase,
    val objectMapper: ObjectMapper,
    val count: Boolean,
    private val exceptionHandler: CoroutineExceptionHandler,
) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val escapedId: String = ENVELOPE_MAPPER.writeValueAsString(id)
}

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(500)

    private lateinit var appScope: CoroutineScope
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private lateinit var reportOutOfMemory: (OutOfMemoryError) -> Unit

    /**
     * Hands the registry the app scope, the exception handler of each subscription's
     * scope and the out-of-memory report sink. Called once from [com.greenart7c3.citrine.Citrine]'s
     * constructor, before the ContentProvider or the relay can store an event.
     */
    fun install(
        appScope: CoroutineScope,
        exceptionHandler: CoroutineExceptionHandler,
        reportOutOfMemory: (OutOfMemoryError) -> Unit,
    ) {
        this.appScope = appScope
        this.exceptionHandler = exceptionHandler
        this.reportOutOfMemory = reportOutOfMemory
    }

    // NIP-01 (https://github.com/nostr-protocol/nips/blob/master/01.md): subscription ids are per connection; the wire id stays the client-sent one.
    private fun cacheKey(connection: Connection, id: String): String = "${connection.name}:$id"

    fun count(): Int = subscriptions.size()

    @OptIn(DelicateCoroutinesApi::class)
    fun executeAll(dbEvent: EventWithTags, connection: Connection?) {
        appScope.launch(Dispatchers.IO) {
            val event = dbEvent.toEvent()
            var eventJsonStr: String? = null
            var sentEvent = false
            for (manager in subscriptions.snapshot().values) {
                val sub = manager.subscription
                if (sub.count) continue
                if (sub.connection.session.outgoing.isClosedForSend) {
                    close(sub.connection, sub.id)
                    continue
                }
                if (sub.filters.any { it.test(event) } && GroupManager.canRead(event, sub.connection)) {
                    val json = eventJsonStr ?: ENVELOPE_MAPPER.writeValueAsString(event.toJsonObject()).also { eventJsonStr = it }
                    sub.connection.trySend("[\"EVENT\",${sub.escapedId},$json]")
                    sentEvent = true
                }
            }
            if (event.isEphemeral()) {
                if (!sentEvent && Settings.sendMuteResponse) {
                    connection?.send(
                        CommandResult.mute(event).toJson(),
                    )
                } else {
                    connection?.send(CommandResult.ok(event).toJson())
                }
            }
        }
    }

    fun closeAll(connectionName: String) {
        Log.d(Citrine.TAG, "finalizing subscriptions from $connectionName")
        subscriptions.snapshot().entries.forEach { (key, manager) ->
            if (manager.subscription.connection.name == connectionName) {
                Log.d(Citrine.TAG, "closing subscription $key")
                manager.subscription.scope.cancel()
                subscriptions.remove(key)
            }
        }
    }

    fun closeAll() {
        subscriptions.snapshot().forEach { (key, manager) ->
            manager.subscription.scope.cancel()
            subscriptions.remove(key)
        }
    }

    fun close(connection: Connection, subscriptionId: String) {
        val key = cacheKey(connection, subscriptionId)
        subscriptions.get(key)?.subscription?.scope?.cancel()
        subscriptions.remove(key)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun containsConnection(connection: Connection): Boolean = subscriptions.snapshot().values.any { it.subscription.connection.name == connection.name && !it.subscription.connection.session.outgoing.isClosedForSend }

    /**
     * Runs [filters] for [connection] and, for a REQ, registers the subscription for live
     * fanout, replacing the connection's subscription with the same id. NIP-45 COUNT
     * subscriptions ([count]) answer once and are never registered, so they neither evict a
     * same-id REQ subscription nor receive live EVENT fanout.
     */
    suspend fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        connection: Connection,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
        count: Boolean,
    ) {
        if (!count) {
            close(connection, subscriptionId)
        }
        val manager = SubscriptionManager(
            Subscription(
                subscriptionId,
                connection,
                filters,
                appDatabase,
                objectMapper,
                count,
                exceptionHandler,
            ),
            reportOutOfMemory,
        )
        if (!count) {
            subscriptions.put(
                cacheKey(connection, subscriptionId),
                manager,
            )
        }
        manager.execute()
    }
}
