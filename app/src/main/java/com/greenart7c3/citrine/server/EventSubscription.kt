package com.greenart7c3.citrine.server

import android.util.Log
import android.util.LruCache
import com.greenart7c3.citrine.Citrine
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.RelayMessage

data class Subscription(
    val id: String,
    val connection: Connection?,
    val filters: List<Filter>,
    val count: Boolean,
)

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(500)

    fun executeAll(event: Event, connection: Connection?) {
        if (connection == null) {
            return
        }

        Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            subscriptions.snapshot().values.forEach {
                it.subscription.filters.forEach filter@{ filter ->
                    if (filter.matchEvent(event)) {
                        if (it.subscription.connection?.name == connection.name) {
                            Log.d(Citrine.TAG, "skipping event to same connection")
                            return@filter
                        }

                        it.subscription.connection?.session?.send(
                            RelayMessage.event(
                                it.subscription.id,
                                event,
                            ).asJson(),
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
        filters: List<Filter>,
        connection: Connection?,
        count: Boolean,
    ) {
        close(subscriptionId)
        val manager = SubscriptionManager(
            Subscription(
                subscriptionId,
                connection,
                filters,
                count,
            ),
        )
        subscriptions.put(
            subscriptionId,
            manager,
        )
        manager.execute()
        connection?.session?.send(RelayMessage.eose(subscriptionId).asJson())
    }
}
