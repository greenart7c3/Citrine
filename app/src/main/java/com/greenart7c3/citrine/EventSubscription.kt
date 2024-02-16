package com.greenart7c3.citrine

import android.util.Log
import android.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.vitorpamplona.quartz.utils.TimeUtils

data class Subscription(
    val id: String,
    val connection: Connection,
    val filters: Set<EventFilter>,
    val appDatabase: AppDatabase,
    val objectMapper: ObjectMapper,
    var eventIdsSent: MutableList<String> = mutableListOf(),
    val initialTime: Long = TimeUtils.now()
)

object EventSubscription {
    private val subscriptions = LruCache<String, SubscriptionManager>(30)

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
        subscriptions[subscriptionId]?.finalize()
        subscriptions.remove(subscriptionId)
        Log.d("subscriptions", subscriptions.size().toString())
    }

    fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        connection: Connection,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper
    ) {
        if (filters != subscriptions[subscriptionId]?.subscription?.filters) {
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
                        objectMapper
                    )
                )
            )
        }
    }
}
