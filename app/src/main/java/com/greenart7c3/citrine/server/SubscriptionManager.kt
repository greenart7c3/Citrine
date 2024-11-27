package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(val subscription: Subscription) {
    suspend fun execute() {
        if (subscription.connection?.session?.outgoing?.isClosedForSend == true) {
            EventSubscription.close(subscription.id)
            Log.d(Citrine.TAG, "cancelling subscription isClosedForSend: ${subscription.id}")
            return
        }

        for (filter in subscription.filters) {
            try {
                EventRepository.subscribe(
                    subscription,
                    filter,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                Log.d(Citrine.TAG, "Error reading data from database $filter", e)
                subscription.connection?.session?.send(
                    NoticeResult.invalid("Error reading data from database").toJson(),
                )
            }
        }
    }
}
