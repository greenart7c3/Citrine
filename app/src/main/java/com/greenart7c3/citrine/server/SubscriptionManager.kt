package com.greenart7c3.citrine.server

import android.util.Log
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(val subscription: Subscription) {
    private suspend fun execute() {
        if (subscription.connection.session.outgoing.isClosedForSend) {
            EventSubscription.close(subscription.id)
            Log.d("timer", "cancelling subscription isClosedForSend: ${subscription.id}")
            return
        }

        val currentJob = GlobalScope.launch(Dispatchers.IO) {
            for (filter in subscription.filters) {
                try {
                    runBlocking {
                        EventRepository.subscribe(
                            subscription,
                            filter,
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    Log.d("error", "Error reading data from database", e)
                    subscription.connection.session.send(
                        NoticeResult.invalid("Error reading data from database").toJson(),
                    )
                }
            }
        }
        runBlocking { currentJob.join() }
    }

    init {
        runBlocking {
            execute()
            subscription.connection.session.send(EOSE(subscription.id).toJson())
        }
    }
}
