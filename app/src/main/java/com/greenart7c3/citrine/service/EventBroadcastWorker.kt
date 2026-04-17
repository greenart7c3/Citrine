package com.greenart7c3.citrine.service

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.storage.EventStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
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

            val store = EventStore.getInstance(applicationContext)
            val event = store.getById(eventId)
            if (event == null) {
                Log.e(Citrine.TAG, "EventBroadcastWorker: Event $eventId not found in database")
                return@withContext Result.failure()
            }

            Log.d(Citrine.TAG, "EventBroadcastWorker: Broadcasting event $eventId to relays")

            val relays = getRelaysForEvent(event, store)
            if (relays.isNullOrEmpty()) {
                Log.d(Citrine.TAG, "EventBroadcastWorker: No relays found for event $eventId")
                return@withContext Result.success()
            }

            if (!Citrine.instance.client.isActive()) {
                Citrine.instance.client.connect()
            }

            val result = Citrine.instance.client.publishAndConfirm(event, relayList = relays)
            Citrine.instance.client.disconnect()
            Log.d(Citrine.TAG, "EventBroadcastWorker: Success: $result broadcast event $eventId to relays")
            if (result) {
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "EventBroadcastWorker: Failed to broadcast event", e)
            Result.retry()
        }
    }

    private fun getRelaysForEvent(event: Event, store: EventStore): Set<NormalizedRelayUrl>? {
        val eventRelays = if (event.kind == AdvertisedRelayListEvent.KIND) {
            (event as AdvertisedRelayListEvent).writeRelays()
        } else {
            null
        }

        val relaysFromEvent = eventRelays?.mapNotNull { urlString ->
            RelayUrlNormalizer.normalizeOrNull(urlString)?.let {
                NormalizedRelayUrl(url = it.url)
            }
        }

        if (!relaysFromEvent.isNullOrEmpty()) {
            return relaysFromEvent.toSet()
        }

        val outboxRelays = store.getAdvertisedRelayList(event.pubKey) as? AdvertisedRelayListEvent
        val writeRelays = outboxRelays?.writeRelays()?.mapNotNull { relay ->
            RelayUrlNormalizer.normalizeOrNull(relay)?.let {
                NormalizedRelayUrl(url = it.url)
            }
        }

        return writeRelays?.toSet()
    }
}
