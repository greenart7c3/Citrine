package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(val subscription: Subscription) {
    suspend fun execute() {
        if (subscription.connection.session.outgoing.isClosedForSend == true) {
            EventSubscription.close(subscription.id)
            Log.d(Citrine.TAG, "cancelling subscription isClosedForSend: ${subscription.id}")
            return
        }

        for (filter in subscription.filters) {
            if (Settings.authEnabled) {
                for (kind in filter.kinds) {
                    if (KINDS_PRIVATE_EVENTS.contains(kind)) {
                        if (subscription.connection.users.isEmpty()) {
                            Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
                            subscription.connection.session.trySend(ClosedResult.required(subscription.id).toJson())
                            return
                        }

                        val senders = filter.authors
                        val receivers = filter.tags.filter { it.key == "p" }
                        if (!senders.any { subscription.connection.users.contains(it) } && !receivers.any { it.value.any { subscription.connection.users.contains(it) } }) {
                            Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} senders: $senders receivers: $receivers authed users: ${subscription.connection.users} filter: $filter")
                            subscription.connection.session.trySend(ClosedResult.restricted(subscription.id).toJson())
                            return
                        }
                    }
                }

                if (filter.kinds.isEmpty() && (filter.tags.contains("p") || filter.authors.isNotEmpty())) {
                    if (subscription.connection.users.isEmpty()) {
                        Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
                        subscription.connection.session.trySend(ClosedResult.required(subscription.id).toJson())
                        return
                    }

                    val senders = filter.authors
                    val receivers = filter.tags.filter { it.key == "#p" }
                    if (!senders.any { subscription.connection.users.contains(it) } && !receivers.any { it.value.any { subscription.connection.users.contains(it) } }) {
                        Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} senders: $senders receivers: $receivers authed user: ${subscription.connection.users} filter: $filter")
                        subscription.connection.session.trySend(ClosedResult.restricted(subscription.id).toJson())
                        return
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
                subscription.connection.session.trySend(
                    NoticeResult.invalid("Error reading data from database").toJson(),
                )
            }
        }
    }
}
