package com.greenart7c3.citrine

import EOSE
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

data class Subscription(
    val id: String,
    val session: DefaultWebSocketServerSession
)

object EventSubscription {
    private val subscriptions: MutableMap<String, Subscription> = ConcurrentHashMap()

    fun close(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    suspend fun subscribe(subscriptionId: String, filters: Set<EventFilter>, session: DefaultWebSocketServerSession, context: Context, objectMapper: ObjectMapper) {
        subscriptions.plus(
            Pair(
                subscriptionId,
                Subscription(
                    subscriptionId,
                    session
                )
            )
        )

        for (filter in filters) {
            try {
                runBlocking {
                    EventRepository.subscribe(subscriptionId, filter, session, context, objectMapper)
                }
            } catch (e: Exception) {
                Log.d("error", "Error reading data from database", e)
                session.send(NoticeResult.invalid("Error reading data from database").toJson())
            }
        }
        session.send(EOSE(subscriptionId).toJson())
    }
}
