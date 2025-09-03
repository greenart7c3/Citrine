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
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.downloadFirstEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayState
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object EventDownloader {
    /**
     * Fetches all events authored by [authorPubKey] from [relayUrl], in descending time order,
     * pulling up to [batchSize] (e.g., 500) per request, starting from now and going backwards,
     * until the returned batch has fewer than [batchSize] events.
     *
     * Returns a list of Events.
     */
    suspend fun fetchAllEventsByUserPaginated(
        scope: CoroutineScope,
        client: NostrClient,
        relayUrl: String,
        authorPubKey: String,
        onAuth: (relay: IRelayClient, challenge: String, subId: String) -> Unit,
    ) {
        val normalizedUrl = NormalizedRelayUrl(relayUrl)
        val mutex = Mutex()
        val batchSize = 500
        var completed = false
        val finishedSignal = CompletableDeferred<Unit>()

        val subscription = RelayClientSubscription(
            client = client,
            relayUrl = normalizedUrl,
            authorPubKey = authorPubKey,
            batchSize = batchSize,
            eventConsumer = { event ->
                scope.launch {
                    mutex.withLock {
                        CustomWebSocketService.server?.innerProcessEvent(event, null)
                    }
                }
            },
            onComplete = {
                completed = true
                finishedSignal.complete(Unit)
            },
            onAuthFunc = onAuth,
        )

        // Wait until subscription finishes
        finishedSignal.await()

        if (completed) {
            subscription.closeSubscription()
        }
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
        relays.forEach {
            val filters = listOf(
                Filter(
                    kinds = listOf(AdvertisedRelayListEvent.KIND),
                    authors = listOf(signer.pubKey),
                    limit = 1,
                ),
            )

            Citrine.getInstance().client.downloadFirstEvent(
                subId,
                mapOf(it to filters),
                onResponse = { event ->
                    Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                    runBlocking {
                        CustomWebSocketService.server?.innerProcessEvent(event, null)
                    }
                    result = event as AdvertisedRelayListEvent
                    finishedRelays[it.url] = true
                },
            )
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
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
        relays.forEach {
            val filters = listOf(
                Filter(
                    kinds = listOf(ContactListEvent.KIND),
                    authors = listOf(signer.pubKey),
                    limit = 1,
                ),
            )

            Citrine.getInstance().client.downloadFirstEvent(
                subId,
                mapOf(it to filters),
                onResponse = { event ->
                    Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                    runBlocking {
                        CustomWebSocketService.server?.innerProcessEvent(event, null)
                    }
                    result = event as ContactListEvent
                    finishedRelays[it.url] = true
                },
            )
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
        }
        Citrine.getInstance().client.close(subId)
        return result
    }

    suspend fun fetchEvents(
        signer: NostrSigner,
        relays: List<NormalizedRelayUrl>,
        onAuth: (relay: IRelayClient, challenge: String, subId: String) -> Unit,
        scope: CoroutineScope,
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
                    scope,
                    Citrine.getInstance().client,
                    it.url,
                    signer.pubKey,
                    onAuth = { relay, challenge, subId ->
                        onAuth(relay, challenge, subId)
                    },
                )
                Citrine.getInstance().client.buildRelay(it).disconnect()
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
    private val onComplete: (() -> Unit)? = null,
    private val onAuthFunc: (relay: IRelayClient, challenge: String, subId: String) -> Unit,
) : IRelayClientListener {
    private var subId = newSubId()
    private var until: Long = (System.currentTimeMillis() / 1000)
    private var oldestTimestamp: Long = until
    private val receivedEvents = mutableListOf<Event>()

    init {
        client.subscribe(this)
        updateFilter()
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

    override fun onAuth(relay: IRelayClient, challenge: String) {
        Log.d("RelayClientSubscription", "onAuth: ${relay.url}, $challenge")
        onAuthFunc(relay, challenge, subId)
        super.onAuth(relay, challenge)
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

        if (receivedEvents.isEmpty()) {
            Log.d("RelayClientSubscription", "Finished paginating, closing subscription.")
            closeSubscription()
            onComplete?.invoke()
            return
        }

        receivedEvents.clear()
        oldestTimestamp -= 1
        until = oldestTimestamp
        closeSubscription()

        updateFilter()
    }

    fun updateFilter() {
        subId = newSubId()
        val filter = Filter(
            authors = listOf(authorPubKey),
            limit = batchSize,
            until = until,
        )

        Log.d("RelayClientSubscription", "Requesting batch with until=$until from relay $relayUrl")
        client.openReqSubscription(subId, mapOf(relayUrl to listOf(filter)))
        val relay = client.buildRelay(relayUrl)
        if (!relay.isConnected()) {
            client.buildRelay(relayUrl).connectAndSyncFiltersIfDisconnected()
        }
    }

    fun closeSubscription() {
        client.close(subId)
    }
}
