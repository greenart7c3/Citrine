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
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

object EventDownloader {
    /**
     * Fetches all events authored by [authorPubKey] from [relayUrl], descending by time,
     * pulling up to [batchSize] (e.g., 500) per request, starting from now and going backwards,
     * until the returned batch has fewer than [batchSize] events.
     *
     * Each completed batch is handed to [sink] for persistence, so memory stays bounded to
     * one in-flight batch per relay instead of accumulating the full set before writing.
     */
    suspend fun fetchAllEventsByUserPaginated(
        client: NostrClient,
        relayUrl: String,
        authorPubKey: String,
        downloadTaggedEvents: Boolean,
        batchSize: Int = 500,
        sink: suspend (List<Event>) -> Unit,
    ) {
        val normalizedUrl = NormalizedRelayUrl(relayUrl)
        var completed = false
        var finishedSignal = CompletableDeferred<Unit>()
        val bufferLock = Any()
        var buffer = ArrayList<Event>(batchSize)

        val subscription = RelayClientSubscription(
            downloadTaggedEvents = downloadTaggedEvents,
            client = client,
            relayUrl = normalizedUrl,
            authorPubKey = authorPubKey,
            batchSize = batchSize,
            eventConsumer = { event -> synchronized(bufferLock) { buffer.add(event) } },
            onComplete = { received ->
                completed = received == 0
                finishedSignal.complete(Unit)
            },
        )

        try {
            while (!completed) {
                withTimeoutOrNull(30_000) {
                    subscription.updateFilter()
                    finishedSignal.await()
                }
                finishedSignal = CompletableDeferred()
                val toSend = synchronized(bufferLock) {
                    if (buffer.isEmpty()) {
                        null
                    } else {
                        val current = buffer
                        buffer = ArrayList(batchSize)
                        current
                    }
                }
                if (toSend != null) {
                    sink(toSend)
                }
            }
        } finally {
            subscription.closeSubscription()
            client.removeConnectionListener(subscription)
        }
    }

    suspend fun fetchAdvertisedRelayList(
        signer: NostrSigner,
    ): AdvertisedRelayListEvent? {
        var result: AdvertisedRelayListEvent? = null
        val relays = listOf(
            NormalizedRelayUrl(url = "wss://purplepag.es"),
            NormalizedRelayUrl(url = "wss://relay.nostr.band"),
        )

        val subId = newSubId()
        val filters = listOf(
            Filter(
                kinds = listOf(AdvertisedRelayListEvent.KIND),
                authors = listOf(signer.pubKey),
                limit = 1,
            ),
        )
        Citrine.instance.client.connect()
        val event = Citrine.instance.client.fetchFirst(
            subId,
            relays.associateWith { filters },
        )
        event?.let {
            CustomWebSocketService.server?.innerProcessEvent(it, null)
            result = it as AdvertisedRelayListEvent
        }

        Citrine.instance.client.unsubscribe(subId)
        return result
    }

    suspend fun fetchContactList(
        signer: NostrSigner,
    ): ContactListEvent? {
        var result: ContactListEvent? = null
        val relays = listOf(
            NormalizedRelayUrl(url = "wss://purplepag.es"),
            NormalizedRelayUrl(url = "wss://relay.nostr.band"),
        )
        val subId = newSubId()

        val filters = listOf(
            Filter(
                kinds = listOf(ContactListEvent.KIND),
                authors = listOf(signer.pubKey),
                limit = 1,
            ),
        )

        Citrine.instance.client.connect()
        val event = Citrine.instance.client.fetchFirst(
            subId,
            relays.associateWith { filters },
        )
        event?.let {
            CustomWebSocketService.server?.innerProcessEvent(it, null)
            result = it as ContactListEvent
        }
        Citrine.instance.client.unsubscribe(subId)
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
        val targets = relays.filter { !it.url.contains("127.0.0.1") }
        try {
            val sem = Semaphore(3)
            coroutineScope {
                targets.map { relay ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            setProgress("loading events from ${relay.url}")
                            fetchAllEventsByUserPaginated(
                                client = Citrine.instance.client,
                                relayUrl = relay.url,
                                authorPubKey = signer.pubKey,
                                downloadTaggedEvents = downloadTaggedEvents,
                                sink = { batch ->
                                    CustomWebSocketService.server?.innerProcessEventBatch(batch)
                                },
                            )
                        }
                    }
                }.awaitAll()
            }

            setProgress("Finished loading events from ${targets.size} relays")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, e.message ?: "", e)
            setProgress("Failed to load events")
        } finally {
            Citrine.isImportingEvents = false
            Citrine.instance.client.disconnect()
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
) : RelayConnectionListener {
    private var subId = newSubId()
    private var until: Long = (System.currentTimeMillis() / 1000)
    private var oldestTimestamp: Long = until
    private var received: Int = 0

    init {
        client.addConnectionListener(this)
    }

    override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
        if (relay.url == relayUrl) {
            Log.d("RelayClientSubscription", "onCannotConnect: ${relay.url}, $errorMessage")
            onComplete?.invoke(received)
            received = 0
        }
        super.onCannotConnect(relay, errorMessage)
    }

    override fun onDisconnected(relay: IRelayClient) {
        if (relay.url == relayUrl) {
            Log.d("RelayClientSubscription", "Disconnected: ${relay.url}")
            onComplete?.invoke(received)
            received = 0
        }
        super.onDisconnected(relay)
    }

    override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
        if (msg is EventMessage && this.subId == msg.subId) {
            received++
            if (msg.event.createdAt < oldestTimestamp) {
                oldestTimestamp = msg.event.createdAt
            }
            eventConsumer(msg.event)
        }

        if (msg is EoseMessage && this.subId == msg.subId) {
            onComplete?.invoke(received)
            received = 0
            oldestTimestamp -= 1
            until = oldestTimestamp
        }

        if (msg is ClosedMessage) {
            onComplete?.invoke(received)
            received = 0
        }

        super.onIncomingMessage(relay, msgStr, msg)
    }

    override fun onSent(relay: IRelayClient, cmdStr: String, cmd: Command, success: Boolean) {
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

        client.subscribe(subId, mapOf(relayUrl to filters))
    }

    fun closeSubscription() {
        client.unsubscribe(subId)
    }
}
