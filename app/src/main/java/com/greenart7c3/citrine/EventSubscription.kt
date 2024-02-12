package com.greenart7c3.citrine

import EOSE
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.send
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

data class Subscription(
    val id: String,
    val session: DefaultWebSocketServerSession,
    val filters: Set<EventFilter>
)

object EventSubscription {
    private val subscriptions: MutableMap<String, Subscription> = ConcurrentHashMap()

    fun close(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun subscribe(
        subscriptionId: String,
        filters: Set<EventFilter>,
        session: DefaultWebSocketServerSession,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
        self: Boolean = false
    ) {
        if (self && filters != subscriptions[subscriptionId]?.filters) {
            Log.d("filters", "closed")
            return
        } else {
            close(subscriptionId)
            subscriptions[subscriptionId] = Subscription(
                subscriptionId,
                session,
                filters
            )

            for (filter in filters) {
                try {
                    runBlocking {
                        EventRepository.subscribe(
                            subscriptionId,
                            filter,
                            session,
                            appDatabase,
                            objectMapper
                        )
                    }
                } catch (e: Exception) {
                    Log.d("error", "Error reading data from database", e)
                    session.send(
                        NoticeResult.invalid("Error reading data from database").toJson()
                    )
                }
            }
            session.send(EOSE(subscriptionId).toJson())

            GlobalScope.launch(Dispatchers.IO) {
                delay(5000)
                subscriptions[subscriptionId] = Subscription(
                    subscriptionId,
                    session,
                    filters.map {
                        val since = it.since?.plus(1) ?: (System.currentTimeMillis() / 1000).toInt()
                        it.since = since
                        it
                    }.toSet()
                )

                val subscription = subscriptions[subscriptionId]
                if (subscription != null) {
                    subscribe(
                        subscription.id,
                        subscription.filters,
                        subscription.session,
                        appDatabase,
                        objectMapper,
                        true
                    )
                }
            }
        }
    }
}
