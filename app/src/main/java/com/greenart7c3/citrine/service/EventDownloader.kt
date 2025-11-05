package com.greenart7c3.citrine.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayState
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import io.ktor.util.collections.ConcurrentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object EventDownloader {
    /**
     * Fetches all events authored by [authorPubKey] from [relayUrl], in descending time order,
     * pulling up to [batchSize] (e.g., 500) per request, starting from now and going backwards,
     * until the returned batch has fewer than [batchSize] events.
     *
     * Returns a list of Events.
     */
    suspend fun fetchAllEventsByUserPaginated(
        client: NostrClient,
        relayUrl: String,
        authorPubKey: String,
    ) {
        val normalizedUrl = NormalizedRelayUrl(relayUrl)
        val batchSize = 500
        var completed = false
        var finishedSignal = CompletableDeferred<Unit>()
        val events = ConcurrentSet<Event>()

        val subscription = RelayClientSubscription(
            client = client,
            relayUrl = normalizedUrl,
            authorPubKey = authorPubKey,
            batchSize = batchSize,
            eventConsumer = { event ->
                events.add(event)
            },
            onComplete = { events ->
                completed = events == 0
                finishedSignal.complete(Unit)
            },
        )

        client.disconnect()
        delay(2000)
        client.connect()

        while (!completed) {
            // Wait until subscription finishes
            withTimeoutOrNull(30000) {
                subscription.updateFilter()
                finishedSignal.await()
            }
            finishedSignal = CompletableDeferred()
            events.forEach {
                CustomWebSocketService.server?.innerProcessEvent(it, null)
            }
            events.clear()
        }
        subscription.closeSubscription()
        client.unsubscribe(subscription)
    }

    suspend fun fetchAdvertisedRelayList(
        signer: NostrSigner,
    ): AdvertisedRelayListEvent? {
        var result: AdvertisedRelayListEvent? = null
        val relays = listOf(
            NormalizedRelayUrl(
                url = "wss://purplepag.es",
            ),
            NormalizedRelayUrl(
                url = "wss://relay.nostr.band",
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val subId = newSubId()
        val filters = listOf(
            Filter(
                kinds = listOf(AdvertisedRelayListEvent.KIND),
                authors = listOf(signer.pubKey),
                limit = 1,
            ),
        )
        Citrine.getInstance().client.connect()
        val event = Citrine.getInstance().client.downloadFirstEvent2(
            subId,
            relays.associateWith {
                filters
            },
        )
        event?.let {
            Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
            CustomWebSocketService.server?.innerProcessEvent(event, null)
            result = event as AdvertisedRelayListEvent
        }

        Citrine.getInstance().client.close(subId)
        return result
    }

    suspend fun fetchContactList(
        signer: NostrSigner,
    ): ContactListEvent? {
        var result: ContactListEvent? = null
        val relays = listOf(
            NormalizedRelayUrl(
                url = "wss://purplepag.es",
            ),
            NormalizedRelayUrl(
                url = "wss://relay.nostr.band",
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }
        val subId = newSubId()

        val filters = listOf(
            Filter(
                kinds = listOf(ContactListEvent.KIND),
                authors = listOf(signer.pubKey),
                limit = 1,
            ),
        )

        Citrine.getInstance().client.connect()
        val event = Citrine.getInstance().client.downloadFirstEvent2(
            subId,
            relays.associateWith {
                filters
            },
        )
        event?.let {
            Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
            CustomWebSocketService.server?.innerProcessEvent(event, null)
            result = event as ContactListEvent
        }
        Citrine.getInstance().client.close(subId)
        return result
    }

    suspend fun fetchEvents(
        signer: NostrSigner,
        relays: List<NormalizedRelayUrl>,
    ) {
        if (!Citrine.getInstance().client.isActive()) {
            Citrine.getInstance().client.connect()
        }
        try {
            val finishedLoading = mutableMapOf<String, Boolean>()

            relays.filter { !it.url.contains("127.0.0.1") }.forEach {
                finishedLoading[it.url] = false
            }

            relays.forEach {
                setProgress("loading events from ${it.url}")
                fetchAllEventsByUserPaginated(
                    Citrine.getInstance().client,
                    it.url,
                    signer.pubKey,
                )
            }

            Citrine.getInstance().client.disconnect()

            setProgress("Finished loading events from ${relays.size} relays")
            Citrine.isImportingEvents = false
        } catch (e: Exception) {
            Citrine.isImportingEvents = false
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, e.message ?: "", e)
            setProgress("Failed to load events")
        }
    }

    fun setProgress(message: String) {
        val notificationManager = NotificationManagerCompat.from(Citrine.getInstance())

        if (message.isBlank()) {
            notificationManager.cancel(2)
            return
        }

        val channel = NotificationChannelCompat.Builder(
            "citrine",
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName("Citrine")
            .build()

        notificationManager.createNotificationChannel(channel)

        val copyIntent = Intent(Citrine.getInstance(), ClipboardReceiver::class.java)
        copyIntent.putExtra("job", "cancel")

        val copyPendingIntent = PendingIntent.getBroadcast(
            Citrine.getInstance(),
            0,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(Citrine.getInstance(), "citrine")
            .setContentTitle("Citrine")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_launcher_background, Citrine.getInstance().getString(R.string.cancel), copyPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(Citrine.getInstance(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
    }
}

class RelayClientSubscription(
    private val client: NostrClient,
    private val relayUrl: NormalizedRelayUrl,
    private val authorPubKey: String,
    private val batchSize: Int = 500,
    private val eventConsumer: (Event) -> Unit,
    private val onComplete: ((Int) -> Unit)? = null,
) : IRelayClientListener {
    private var subId = newSubId()
    private var until: Long = (System.currentTimeMillis() / 1000)
    private var oldestTimestamp: Long = until
    private val receivedEvents = mutableListOf<Event>()

    init {
        client.subscribe(this)
    }

    override fun onRelayStateChange(relay: IRelayClient, type: RelayState) {
        Log.d("RelayClientSubscription", "onRelayStateChange: ${relay.url}, $type")
        super.onRelayStateChange(relay, type)
    }

    override fun onClosed(relay: IRelayClient, subId: String, message: String) {
        Log.d("RelayClientSubscription", "onClosed: ${relay.url}, $subId, $message")
        super.onClosed(relay, subId, message)
    }

    override fun onError(relay: IRelayClient, subId: String, error: Error) {
        Log.d("RelayClientSubscription", "onError: ${relay.url}, $subId, $error")
        super.onError(relay, subId, error)
    }

    override fun onNotify(relay: IRelayClient, description: String) {
        Log.d("RelayClientSubscription", "onNotify: ${relay.url}, $description")
        super.onNotify(relay, description)
    }

    override fun onSendResponse(relay: IRelayClient, eventId: String, success: Boolean, message: String) {
        Log.d("RelayClientSubscription", "onSendResponse: ${relay.url}, $eventId, $success, $message")
        super.onSendResponse(relay, eventId, success, message)
    }

    override fun onAuth(relay: IRelayClient, challenge: String) {
        Log.d("RelayClientSubscription", "onAuth: ${relay.url}, $challenge")
        super.onAuth(relay, challenge)
    }

    override fun onAuthed(relay: IRelayClient, eventId: String, success: Boolean, message: String) {
        Log.d("RelayClientSubscription", "onAuthed: ${relay.url}, $eventId, $success, $message")
        super.onAuthed(relay, eventId, success, message)
    }

    override fun onEvent(
        relay: IRelayClient,
        subId: String,
        event: Event,
        arrivalTime: Long,
        afterEOSE: Boolean,
    ) {
        if (this.subId == subId) {
            receivedEvents.add(event)
            if (event.createdAt < oldestTimestamp) {
                oldestTimestamp = event.createdAt
            }
            eventConsumer(event)
        }
    }

    override fun onSend(relay: IRelayClient, msg: String, success: Boolean) {
        Log.d("RelayClientSubscription", "onSend: ${relay.url}, $msg, $success")
    }

    override fun onEOSE(relay: IRelayClient, subId: String, arrivalTime: Long) {
        if (this.subId != subId) return

        Log.d("RelayClientSubscription", "onEOSE: Received ${receivedEvents.size} events $arrivalTime")

        onComplete?.invoke(receivedEvents.size)
        receivedEvents.clear()
        oldestTimestamp -= 1
        until = oldestTimestamp
    }

    fun updateFilter() {
        val filter = Filter(
            authors = listOf(authorPubKey),
            limit = batchSize,
            until = until,
        )

        Log.d("RelayClientSubscription", "Requesting batch with until=$until from relay $relayUrl")
        client.openReqSubscription(subId, mapOf(relayUrl to listOf(filter)))
    }

    fun closeSubscription() {
        client.close(subId)
    }
}

suspend fun INostrClient.downloadFirstEvent2(
    subscriptionId: String = newSubId(),
    filters: Map<NormalizedRelayUrl, List<Filter>>,
): Event? {
    val resultChannel = Channel<Event>()

    val listener =
        object : IRelayClientListener {
            override fun onError(relay: IRelayClient, subId: String, error: Error) {
                Log.d("RelayClientSubscription", "onError: ${relay.url.url}, $subId, $error")
                super.onError(relay, subId, error)
            }

            override fun onRelayStateChange(relay: IRelayClient, type: RelayState) {
                Log.d("RelayClientSubscription", "onRelayStateChange: ${relay.url.url}, $type")
                super.onRelayStateChange(relay, type)
            }
            override fun onEvent(
                relay: IRelayClient,
                subId: String,
                event: Event,
                arrivalTime: Long,
                afterEOSE: Boolean,
            ) {
                if (subId == subscriptionId) {
                    resultChannel.trySend(event)
                }
            }
        }

    subscribe(listener)

    val result =
        withTimeoutOrNull(30000) {
            openReqSubscription(subscriptionId, filters)
            delay(2000)
            reconnect()
            resultChannel.receive()
        }

    close(subscriptionId)
    unsubscribe(listener)
    resultChannel.close()

    return result
}
