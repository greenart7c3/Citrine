package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.utils.KINDS_EVENT_EPHEMERAL
import io.ktor.websocket.send
import kotlinx.coroutines.DelicateCoroutinesApi
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.RelayMessage

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(val subscription: Subscription) {
    private suspend fun isEphemeral(): Boolean {
        return subscription.filters.any {
            return it.asRecord().kinds?.any { kind -> kind == KINDS_EVENT_EPHEMERAL.map { ep -> Kind(ep.toUShort()) } } == true
        }
    }

    suspend fun execute() {
        if (subscription.connection?.session?.outgoing?.isClosedForSend == true) {
            EventSubscription.close(subscription.id)
            Log.d(Citrine.TAG, "cancelling subscription isClosedForSend: ${subscription.id}")
            return
        }

        val isEphemeral = isEphemeral()
        Log.d(Citrine.TAG, "subscription ${subscription.id} is ephemeral: $isEphemeral")

        val database = if (isEphemeral) {
            AppDatabase.getNostrEphemeralDatabase()
        } else {
            AppDatabase.getNostrDatabase()
        }

        if (subscription.count) {
            val count = database.count(subscription.filters)
            subscription.connection?.session?.send(
                RelayMessage.count(subscription.id, count.toDouble()).asJson(),
            )
            return
        } else {
            val events = database.query(subscription.filters)
            events.toVec().forEach { event ->
                if (!event.isExpired()) {
                    val filtersString = subscription.filters.joinToString(",", prefix = "[", postfix = "]") { it.asJson() }

                    Log.d(Citrine.TAG, "sending event ${event.id().toHex()} subscription ${subscription.id} filter $filtersString")

                    subscription.connection?.session?.send(
                        RelayMessage.event(subscription.id, event).asJson(),
                    )
                } else if (Settings.deleteExpiredEvents) {
                    Log.d(Citrine.TAG, "event ${event.id().toHex()} is expired deleting")
                    AppDatabase.getNostrDatabase().delete(
                        Filter().id(event.id()),
                    )
                }
            }
        }
    }
}
