package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import kotlin.time.measureTime
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
                val time = measureTime {
                    EventRepository.subscribe(
                        subscription,
                        filter,
                    )
                }
                Log.d(Citrine.TAG, "Subscription (${EventSubscription.count()}) ${subscription.id} took $time to execute $filter")
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                Log.d(Citrine.TAG, "Error reading data from database $filter", e)
                subscription.connection?.session?.trySend(
                    NoticeResult.invalid("Error reading data from database").toJson(),
                )
            }
        }
    }
}
