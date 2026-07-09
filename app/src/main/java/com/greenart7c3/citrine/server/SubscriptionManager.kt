package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(
    val subscription: Subscription,
) {
    suspend fun execute() {
        subscription.scope.launch(Dispatchers.IO) {
            if (subscription.connection.session.outgoing.isClosedForSend) {
                EventSubscription.close(subscription.id)
                Log.d(Citrine.TAG, "cancelling subscription isClosedForSend: ${subscription.id}")
                return@launch
            }

            for (filter in subscription.filters) {
                when (AuthGate.check(filter, subscription.connection)) {
                    AuthGate.Denial.AUTH_REQUIRED -> {
                        Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
                        subscription.connection.send(ClosedResult.required(subscription.id).toJson())
                        return@launch
                    }
                    AuthGate.Denial.RESTRICTED -> {
                        Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} filter: $filter authed users: ${subscription.connection.users}")
                        subscription.connection.send(ClosedResult.restricted(subscription.id).toJson())
                        return@launch
                    }
                    null -> Unit
                }

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
                    subscription.connection.send(
                        NoticeResult.invalid("Error reading data from database").toJson(),
                    )
                }
            }

            subscription.connection.send(EOSE(subscription.id).toJson())
        }
    }
}
