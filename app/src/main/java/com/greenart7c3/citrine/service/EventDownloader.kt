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
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.ClosedMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import io.ktor.util.collections.ConcurrentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        downloadTaggedEvents: Boolean,
    ) {
        val normalizedUrl = NormalizedRelayUrl(relayUrl)
        val batchSize = 500
        var completed = false
        var finishedSignal = CompletableDeferred<Unit>()
        val events = ConcurrentSet<Event>()

        val subscription = RelayClientSubscription(
            downloadTaggedEvents = downloadTaggedEvents,
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
        Citrine.instance.client.connect()
        val event = Citrine.instance.client.downloadFirstEvent(
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

        Citrine.instance.client.close(subId)
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

        Citrine.instance.client.connect()
        val event = Citrine.instance.client.downloadFirstEvent(
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
        Citrine.instance.client.close(subId)
        return result
    }

    suspend fun fetchEvents(
        signer: NostrSigner,
        relays: List<NormalizedRelayUrl>,
        downloadTaggedEvents: Boolean,
    ) {
        if (!Citrine.instance.client.isActive()) {
            Citrine.instance.client.connect()
        }
        try {
            val finishedLoading = mutableMapOf<String, Boolean>()

            relays.filter { !it.url.contains("127.0.0.1") }.forEach {
                finishedLoading[it.url] = false
            }

            relays.forEach {
                setProgress("loading events from ${it.url}")
                fetchAllEventsByUserPaginated(
                    Citrine.instance.client,
                    it.url,
                    signer.pubKey,
                    downloadTaggedEvents = downloadTaggedEvents,
                )
            }

            Citrine.instance.client.disconnect()

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
        val notificationManager = NotificationManagerCompat.from(Citrine.instance)

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

        val copyIntent = Intent(Citrine.instance, ClipboardReceiver::class.java)
        copyIntent.putExtra("job", "cancel")

        val copyPendingIntent = PendingIntent.getBroadcast(
            Citrine.instance,
            0,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(Citrine.instance, "citrine")
            .setContentTitle("Citrine")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_launcher_background, Citrine.instance.getString(R.string.cancel), copyPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(Citrine.instance, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
    }
}

class RelayClientSubscription(
    private val downloadTaggedEvents: Boolean,
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

    override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
        Log.d("RelayClientSubscription", "onCannotConnect: ${relay.url}, $errorMessage")
        onComplete?.invoke(receivedEvents.size)
        receivedEvents.clear()
        super.onCannotConnect(relay, errorMessage)
    }

    override fun onConnecting(relay: IRelayClient) {
        Log.d("RelayClientSubscription", "Connecting: ${relay.url}")
        super.onConnecting(relay)
    }

    override fun onDisconnected(relay: IRelayClient) {
        Log.d("RelayClientSubscription", "Disconnected: ${relay.url}")
        onComplete?.invoke(receivedEvents.size)
        receivedEvents.clear()
        super.onDisconnected(relay)
    }

    override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
        Log.d("RelayClientSubscription", "onIncomingMessage: ${relay.url}, $msgStr")

        if (msg is EventMessage && this.subId == msg.subId) {
            receivedEvents.add(msg.event)
            if (msg.event.createdAt < oldestTimestamp) {
                oldestTimestamp = msg.event.createdAt
            }
            eventConsumer(msg.event)
        }

        if (msg is EoseMessage && this.subId == msg.subId) {
            onComplete?.invoke(receivedEvents.size)
            receivedEvents.clear()
            oldestTimestamp -= 1
            until = oldestTimestamp
        }

        if (msg is ClosedMessage) {
            onComplete?.invoke(receivedEvents.size)
            receivedEvents.clear()
        }

        super.onIncomingMessage(relay, msgStr, msg)
    }

    override fun onSent(relay: IRelayClient, cmdStr: String, cmd: Command, success: Boolean) {
        Log.d("RelayClientSubscription", "onSendResponse: ${relay.url}, $cmdStr, $success")
        super.onSent(relay, cmdStr, cmd, success)
    }

    fun updateFilter() {
        val filters = mutableListOf(
            Filter(
                authors = listOf(authorPubKey),
                limit = batchSize,
                until = until,
            ),
        )
        if (downloadTaggedEvents) {
            filters.add(
                Filter(
                    limit = batchSize,
                    until = until,
                    tags = mapOf("p" to listOf(authorPubKey)),
                ),
            )
        }

        Log.d("RelayClientSubscription", "Requesting batch with until=$until from relay $relayUrl")
        client.openReqSubscription(subId, mapOf(relayUrl to filters))
    }

    fun closeSubscription() {
        client.close(subId)
    }
}
