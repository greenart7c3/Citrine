package com.greenart7c3.citrine.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
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
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
     *
     * [onBatchDownloaded] is called with the size of every batch once it has been sunk.
     */
    suspend fun fetchAllEventsByUserPaginated(
        client: NostrClient,
        relayUrl: String,
        authorPubKey: String,
        downloadTaggedEvents: Boolean,
        batchSize: Int = 500,
        onBatchDownloaded: ((batchSize: Int) -> Unit)? = null,
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

        var downloaded = 0
        var unansweredRounds = 0

        try {
            while (!completed) {
                val answered = withTimeoutOrNull(REQ_TIMEOUT_MS) {
                    subscription.updateFilter()
                    finishedSignal.await()
                } != null
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
                    downloaded += toSend.size
                    onBatchDownloaded?.invoke(toSend.size)
                }

                if (answered || toSend != null) {
                    // A slow relay that is still streaming events is making progress: keep waiting
                    // on the REQ in flight instead of giving up on it.
                    unansweredRounds = 0
                } else {
                    // Nothing arrived and the relay never answered the REQ. `until` only moves on
                    // EOSE, so the next REQ would carry the exact same filters and the relay
                    // client would dedupe it away (or consider the old one still in flight),
                    // leaving this loop spinning forever. Drop the subscription so the retry is
                    // actually sent, and give up on this relay after a few silent rounds.
                    unansweredRounds++
                    if (unansweredRounds >= MAX_UNANSWERED_ROUNDS) {
                        Log.w(
                            Citrine.TAG,
                            "Giving up on ${normalizedUrl.url} after $unansweredRounds unanswered requests ($downloaded events downloaded)",
                        )
                        break
                    }
                    subscription.restartSubscription()
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
            RelayUrlNormalizer.normalize("wss://purplepag.es/"),
            RelayUrlNormalizer.normalize("wss://user.kindpag.es/"),
            RelayUrlNormalizer.normalize("wss://profiles.nostr1.com/"),
            RelayUrlNormalizer.normalize("wss://directory.yabu.me/"),
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
            storeInBackground(it)
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
            RelayUrlNormalizer.normalize("wss://purplepag.es/"),
            RelayUrlNormalizer.normalize("wss://user.kindpag.es/"),
            RelayUrlNormalizer.normalize("wss://profiles.nostr1.com/"),
            RelayUrlNormalizer.normalize("wss://directory.yabu.me/"),
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
            storeInBackground(it)
            result = it as ContactListEvent
        }
        Citrine.instance.client.unsubscribe(subId)
        return result
    }

    /**
     * Stores an event fetched from an indexer without making the caller wait for Room.
     *
     * The relay-list lookups above only need the event itself; the write (signature check, the
     * replaceable-event cleanup, the history database) must not gate the UI that asked for it.
     */
    private fun storeInBackground(event: Event) {
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                CustomWebSocketService.server?.innerProcessEvent(event, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Citrine.TAG, "Could not store fetched event ${event.id}", e)
            }
        }
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
        val progress = DownloadProgress(targets.size)
        try {
            progress.publish(force = true)
            val sem = Semaphore(3)
            coroutineScope {
                targets.map { relay ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            try {
                                fetchAllEventsByUserPaginated(
                                    client = Citrine.instance.client,
                                    relayUrl = relay.url,
                                    authorPubKey = signer.pubKey,
                                    downloadTaggedEvents = downloadTaggedEvents,
                                    onBatchDownloaded = { size ->
                                        progress.onBatchDownloaded(size)
                                    },
                                    sink = { batch ->
                                        CustomWebSocketService.server?.innerProcessEventBatch(batch)
                                    },
                                )
                            } finally {
                                progress.onRelayFinished()
                            }
                        }
                    }
                }.awaitAll()
            }

            progress.publishFinished()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, e.message ?: "", e)
            setProgress("Failed to load events")
        } finally {
            Citrine.isImportingEvents = false
            Citrine.instance.client.disconnect()
        }
    }

    /**
     * Tracks how many events the download has pulled so far and keeps notification 2 in sync.
     * Notifications are throttled: with a big archive `sink` runs for seconds per batch, and
     * `notify` is a binder round-trip, so republishing on every batch would be wasted work.
     */
    private class DownloadProgress(
        private val relayCount: Int,
    ) {
        private val downloaded = AtomicInteger(0)
        private val finishedRelays = AtomicInteger(0)
        private val lastPublish = AtomicLong(0)

        fun onBatchDownloaded(batchSize: Int) {
            downloaded.addAndGet(batchSize)
            publish(force = false)
        }

        fun onRelayFinished() {
            finishedRelays.incrementAndGet()
            publish(force = true)
        }

        fun publishFinished() {
            setProgress(
                Citrine.instance.getString(
                    R.string.downloading_events_finished,
                    formatCount(downloaded.get()),
                ),
            )
        }

        fun publish(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastPublish.get() < PUBLISH_INTERVAL_MS) return
            lastPublish.set(now)
            setProgress(
                Citrine.instance.getString(
                    R.string.downloading_events_progress,
                    formatCount(downloaded.get()),
                    finishedRelays.get(),
                    relayCount,
                ),
                ongoing = true,
            )
        }

        private fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count.toLong())

        private companion object {
            /** Minimum gap between two progress notifications, in milliseconds. */
            const val PUBLISH_INTERVAL_MS = 1_000L
        }
    }

    fun setProgress(
        message: String,
        ongoing: Boolean = false,
    ) {
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
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, ongoing)
            .addAction(R.drawable.ic_launcher_background, Citrine.instance.getString(R.string.cancel), copyPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(Citrine.instance, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
    }

    private const val REQ_TIMEOUT_MS = 30_000L

    /** How many consecutive unanswered REQs a relay gets before the download gives up on it. */
    private const val MAX_UNANSWERED_ROUNDS = 3
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

    override suspend fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
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

    /**
     * Drops the current subscription so the next [updateFilter] sends a brand new REQ, even when
     * the filters are unchanged. Without this, a REQ that the relay never answered (or that it
     * closed) is deduped by the relay client on the retry, and pagination never resumes.
     */
    fun restartSubscription() {
        client.unsubscribe(subId)
        subId = newSubId()
    }
}
