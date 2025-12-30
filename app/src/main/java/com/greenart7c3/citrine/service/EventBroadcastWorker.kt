package com.greenart7c3.citrine.service

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.sendAndWaitForResponse
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EventBroadcastWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val eventId = inputData.getString("event_id")
            if (eventId == null) {
                Log.e(Citrine.TAG, "EventBroadcastWorker: event_id is null")
                return@withContext Result.failure()
            }

            val database = AppDatabase.getDatabase(applicationContext)
            val dbEvent = database.eventDao().getById(eventId)
            if (dbEvent == null) {
                Log.e(Citrine.TAG, "EventBroadcastWorker: Event $eventId not found in database")
                return@withContext Result.failure()
            }

            val event = dbEvent.toEvent()
            Log.d(Citrine.TAG, "EventBroadcastWorker: Broadcasting event $eventId to relays")

            // Get relays for the event
            val relays = getRelaysForEvent(event, database)
            if (relays.isNullOrEmpty()) {
                Log.d(Citrine.TAG, "EventBroadcastWorker: No relays found for event $eventId")
                return@withContext Result.success()
            }

            // Ensure client is connected
            if (!Citrine.instance.client.isActive()) {
                Citrine.instance.client.connect()
            }

            // Send event to relays
            Citrine.instance.client.sendAndWaitForResponse(event, relayList = relays)
            Log.d(Citrine.TAG, "EventBroadcastWorker: Successfully broadcast event $eventId to relays")

            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "EventBroadcastWorker: Failed to broadcast event", e)
            Result.retry()
        }
    }

    private suspend fun getRelaysForEvent(event: Event, database: AppDatabase): Set<NormalizedRelayUrl>? {
        // Try to get relays from the event itself (e.g., ContactListEvent)
        val eventRelays = try {
            val relaysMethod = event.javaClass.getMethod("relays")
            relaysMethod.invoke(event) as? Map<*, *>
        } catch (e: Exception) {
            null
        }

        val relaysFromEvent = eventRelays?.mapNotNull { entry ->
            val relayEntry = entry as? Map.Entry<*, *>
            val relayUrl = relayEntry?.key
            val relayInfo = relayEntry?.value

            // Check if relayInfo has a write property (could be an object or a Map)
            val canWrite = try {
                when (relayInfo) {
                    is Map<*, *> -> relayInfo["write"] as? Boolean ?: true
                    else -> {
                        // Try to access write property via reflection
                        val writeMethod = relayInfo?.javaClass?.getMethod("getWrite")
                        writeMethod?.invoke(relayInfo) as? Boolean ?: true
                    }
                }
            } catch (e: Exception) {
                true // Default to allowing write if we can't determine
            }

            if (canWrite && relayUrl != null) {
                // relayUrl might be a NormalizedRelayUrl or a String
                val urlString = when (relayUrl) {
                    is String -> relayUrl
                    else -> {
                        // Try to get url property
                        try {
                            val urlMethod = relayUrl.javaClass.getMethod("getUrl")
                            urlMethod.invoke(relayUrl) as? String ?: relayUrl.toString()
                        } catch (e: Exception) {
                            relayUrl.toString()
                        }
                    }
                }
                RelayUrlNormalizer.normalizeOrNull(urlString)?.let {
                    NormalizedRelayUrl(url = it.url)
                }
            } else {
                null
            }
        }

        if (!relaysFromEvent.isNullOrEmpty()) {
            return relaysFromEvent.toSet()
        }

        // Try to get relays from the user's advertised relay list (kind 10002)
        val advertisedRelayList = database.eventDao().getAdvertisedRelayList(event.pubKey)
        val outboxRelays = advertisedRelayList?.toEvent() as? AdvertisedRelayListEvent
        val writeRelays = outboxRelays?.writeRelays()?.mapNotNull { relay ->
            RelayUrlNormalizer.normalizeOrNull(relay)?.let {
                NormalizedRelayUrl(url = it.url)
            }
        }

        return writeRelays?.toSet()
    }
}
