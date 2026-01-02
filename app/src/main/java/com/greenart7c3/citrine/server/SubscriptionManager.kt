package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
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
                // Check pubkey auth - require authentication for private events and filters with p tags/authors
                if (Settings.authEnabled) {
                    for (kind in filter.kinds) {
                        if (KINDS_PRIVATE_EVENTS.contains(kind)) {
                            if (subscription.connection.users.isEmpty()) {
                                Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
                                subscription.connection.trySend(ClosedResult.required(subscription.id).toJson())
                                return@launch
                            }

                            val senders = filter.authors
                            val receivers = filter.tags.filter { it.key == "p" }
                            if (!senders.any { subscription.connection.users.contains(it) } && !receivers.any { it.value.any { receiver -> subscription.connection.users.contains(receiver) } }) {
                                Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} senders: $senders receivers: $receivers authed users: ${subscription.connection.users} filter: $filter")
                                subscription.connection.trySend(ClosedResult.restricted(subscription.id).toJson())
                                return@launch
                            }
                        }
                    }

                    if (filter.kinds.isEmpty() && (filter.tags.contains("p") || filter.authors.isNotEmpty())) {
                        if (subscription.connection.users.isEmpty()) {
                            Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
                            subscription.connection.trySend(ClosedResult.required(subscription.id).toJson())
                            return@launch
                        }

                        val senders = filter.authors
                        val receivers = filter.tags.filter { it.key == "#p" }
                        if (!senders.any { subscription.connection.users.contains(it) } && !receivers.any { it.value.any { receiver -> subscription.connection.users.contains(receiver) } }) {
                            Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} senders: $senders receivers: $receivers authed user: ${subscription.connection.users} filter: $filter")
                            subscription.connection.trySend(ClosedResult.restricted(subscription.id).toJson())
                            return@launch
                        }
                    }
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
                    subscription.connection.trySend(
                        NoticeResult.invalid("Error reading data from database").toJson(),
                    )
                }
            }

            subscription.connection.trySend(EOSE(subscription.id).toJson())
        }
    }
}
