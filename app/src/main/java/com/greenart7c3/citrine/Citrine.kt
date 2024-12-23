package com.greenart7c3.citrine

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.ClipboardReceiver
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.RelayAuthEvent
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import com.vitorpamplona.quartz.utils.TimeUtils
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientMessage
import rust.nostr.sdk.Filter
import rust.nostr.sdk.FilterOptions
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindEnum
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Timestamp

class Citrine : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var isImportingEvents = false
    var job: Job? = null
    var localJob: Job? = null
    var signer = NostrSignerExternal(
        "",
        ExternalSignerLauncher("", ""),
    )

    override fun onCreate() {
        super.onCreate()

        instance = this
        LocalPreferences.loadSettingsFromEncryptedStorage(this)
    }

    fun contentResolverFn(): ContentResolver = contentResolver

    private fun showNotification(message: String) {
        val notificationManager = getInstance().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationChannel = NotificationChannel(
            "2",
            "Relay Sync",
            NotificationManager.IMPORTANCE_MIN,
        )
        val notificationGroup = NotificationChannelGroup("2", "Relay Sync")
        notificationManager.createNotificationChannelGroup(notificationGroup)
        notificationChannel.group = "2"
        notificationManager.createNotificationChannel(notificationChannel)

        val copyIntent = Intent(this, ClipboardReceiver::class.java)
        copyIntent.putExtra("cancel", true)

        val copyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(getInstance(), "2")
            .setContentTitle("Relay Sync")
            .setContentText(message)
            .setChannelId("2")
            .setGroup("2")
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_background, "Cancel", copyPendingIntent)
            .build()
        notificationManager.notify(2, notification)
    }

    suspend fun getAllEventsFromPubKey() {
        val notificationManager = getInstance().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val relays = mutableSetOf<String>()
        var contactList = AppDatabase.getNostrDatabase().query(
            listOf(Filter().author(PublicKey.parse(signer.pubKey)).kind(Kind.fromEnum(KindEnum.ContactList))),
        ).toVec().firstOrNull()

        if (contactList == null) {
            showNotification("loading relays")

            fetchContactList()
            contactList = AppDatabase.getNostrDatabase().query(
                listOf(Filter().author(PublicKey.parse(signer.pubKey)).kind(Kind.fromEnum(KindEnum.ContactList))),
            ).toVec().firstOrNull()

            var generalRelays: Map<String, ContactListEvent.ReadWrite>? = null
            contactList?.let {
                generalRelays = (Event.fromJson(it.asJson()) as ContactListEvent).relays()
            }
            relays.addAll(
                generalRelays?.map { it.key } ?: emptyList(),
            )

            fetchOutbox()
            val advertisedRelayList = AppDatabase.getNostrDatabase().query(
                listOf(Filter().author(PublicKey.parse(signer.pubKey)).kind(Kind.fromEnum(KindEnum.RelayList))),
            ).toVec().firstOrNull()

            advertisedRelayList?.let {
                relays.addAll(
                    it.tags().toVec().map { tag ->
                        tag.asVec()
                    }.mapNotNull { tag ->
                        if (tag.size > 1 && tag[0] == "r") {
                            tag[1]
                        } else {
                            null
                        }
                    },
                )
            }
            fetchEvents(relays)
            notificationManager.cancel(2)
        } else {
            showNotification("loading relays")
            var generalRelays: Map<String, ContactListEvent.ReadWrite>?
            contactList.let {
                generalRelays = (Event.fromJson(it.asJson()) as ContactListEvent).relays()
            }
            relays.addAll(
                generalRelays?.map { it.key } ?: emptyList(),
            )

            var advertisedRelayList = AppDatabase.getNostrDatabase().query(
                listOf(Filter().author(PublicKey.parse(signer.pubKey)).kind(Kind.fromEnum(KindEnum.RelayList))),
            ).toVec().firstOrNull()
            if (advertisedRelayList == null) {
                fetchOutbox()
                advertisedRelayList = AppDatabase.getNostrDatabase().query(
                    listOf(Filter().author(PublicKey.parse(signer.pubKey)).kind(Kind.fromEnum(KindEnum.RelayList))),
                ).toVec().firstOrNull()
                advertisedRelayList?.let {
                    relays.addAll(
                        it.tags().toVec().map { tag ->
                            tag.asVec()
                        }.mapNotNull { tag ->
                            if (tag.size > 1 && tag[0] == "r") {
                                tag[1]
                            } else {
                                null
                            }
                        },
                    )
                }
                fetchEvents(relays)
                notificationManager.cancel(2)
            } else {
                advertisedRelayList.let {
                    relays.addAll(
                        it.tags().toVec().map { tag ->
                            tag.asVec()
                        }.mapNotNull { tag ->
                            if (tag.size > 1 && tag[0] == "r") {
                                tag[1]
                            } else {
                                null
                            }
                        },
                    )
                }
                fetchEvents(relays)
                notificationManager.cancel(2)
            }
        }
        isImportingEvents = false
    }

    private suspend fun fetchContactList() {
        val client = Client()

        client.addRelay("wss://purplepag.es")
        client.addRelay("wss://relay.nostr.band")

        try {
            client.connectWithTimeout(Duration.ofSeconds(30))
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: "", e)
            return
        }
        val events = client.fetchEvents(
            listOf(
                Filter()
                    .author(PublicKey.parse(signer.pubKey))
                    .kind(Kind.fromEnum(KindEnum.ContactList))
                    .limit(1u),
            ),
            Duration.ofSeconds(30),
        )

        events.toVec().forEach { event ->
            CustomWebSocketService.server?.innerProcessEvent(event, null)
        }
        client.disconnect()
    }

    private suspend fun fetchOutbox() {
        val client = Client()

        client.addRelay("wss://purplepag.es")
        client.addRelay("wss://relay.nostr.band")

        try {
            client.connectWithTimeout(Duration.ofSeconds(30))
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: "", e)
            return
        }
        val events = client.fetchEvents(
            listOf(
                Filter()
                    .author(PublicKey.parse(signer.pubKey))
                    .kind(Kind.fromEnum(KindEnum.RelayList))
                    .limit(1u),
            ),
            Duration.ofSeconds(30),
        )

        events.toVec().forEach { event ->
            CustomWebSocketService.server?.innerProcessEvent(event, null)
        }
        client.disconnect()
    }

    private suspend fun fetchEventsFrom(
        client: Client,
        relayUrl: String,
        until: Long,
        onEvent: () -> Unit,
        onDone: () -> Unit,
    ) {
        val eventCount = mutableMapOf<String, Int>()
        var lastEventCreatedAt = until

        localJob = applicationScope.launch {
            val scope = this
            val handler = object : HandleNotification {
                override suspend fun handle(relayUrl: String, subscriptionId: String, event: rust.nostr.sdk.Event) {
                    if ((job?.isActive == false) || (localJob?.isActive == false)) {
                        val notificationManager = getInstance().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(2)
                        return
                    }

                    onEvent()
                    eventCount[relayUrl] = eventCount[relayUrl]?.plus(1) ?: 1
                    lastEventCreatedAt = event.createdAt().asSecs().toLong() - 1

                    CustomWebSocketService.server?.innerProcessEvent(event, null)
                }

                override suspend fun handleMsg(relayUrl: String, msg: RelayMessage) {
                    when (val type = msg.asEnum()) {
                        is RelayMessageEnum.EndOfStoredEvents -> {
                            Log.d("relaySub", "End of stored events from $relayUrl")
                            scope.cancel()
                        }

                        is RelayMessageEnum.Auth -> {
                            Log.d("relaySub", "Auth from $relayUrl")
                            RelayAuthEvent.create(
                                relayUrl,
                                type.challenge,
                                signer,
                                onReady = {
                                    applicationScope.launch {
                                        client.sendMsgTo(
                                            listOf(relayUrl),
                                            ClientMessage.auth(rust.nostr.sdk.Event.fromJson(it.toJson())),
                                        )
                                        val filters = listOf(
                                            Filter()
                                                .author(PublicKey.parse(signer.pubKey))
                                                .until(Timestamp.fromSecs(until.toULong())),
                                        )
                                        client.subscribeTo(listOf(relayUrl), filters, SubscribeAutoCloseOptions().filter(FilterOptions.WaitDurationAfterEose(Duration.ofSeconds(5))))
                                    }
                                },
                            )
                        }
                        is RelayMessageEnum.Closed -> {
                            Log.d("relaySub", "Closed from $relayUrl")
                            // scope.cancel()
                        }
                        is RelayMessageEnum.Count -> {
                            Log.d("relaySub", "Count from $relayUrl: ${type.count}")
                        }
                        is RelayMessageEnum.EventMsg -> {
                        }
                        is RelayMessageEnum.NegErr -> {
                            Log.d("relaySub", "NegErr from $relayUrl: ${type.message}")
                        }
                        is RelayMessageEnum.NegMsg -> {
                            Log.d("relaySub", "NegMsg from $relayUrl: ${type.message}")
                        }
                        is RelayMessageEnum.Notice -> {
                            Log.d("relaySub", "Notice from $relayUrl: ${type.message}")
                        }
                        is RelayMessageEnum.Ok -> {
                            Log.d("relaySub", "Ok from $relayUrl")
                        }
                    }
                }
            }
            client.handleNotifications(
                handler,
            )
        }
        if (client.relays().values.any { !it.isConnected() }) {
            try {
                client.connectWithTimeout(Duration.ofSeconds(30))
            } catch (e: Exception) {
                Log.d(TAG, e.message ?: "", e)
                onDone()
                return
            }
        }

        val filters = listOf(
            Filter()
                .author(PublicKey.parse(signer.pubKey))
                .until(Timestamp.fromSecs(until.toULong())),
        )
        client.subscribeTo(listOf(relayUrl), filters, SubscribeAutoCloseOptions().filter(FilterOptions.ExitOnEose))
        localJob?.join()
        if ((eventCount[relayUrl] ?: 0) > 0) {
            if (lastEventCreatedAt == until) {
                onDone()
            } else {
                fetchEventsFrom(client, relayUrl, lastEventCreatedAt - 1, onEvent, onDone)
            }
        } else {
            onDone()
        }
    }

    private suspend fun fetchEvents(
        relays: Set<String>,
    ) {
        relays.filter { !it.contains("nostr.band") }.forEachIndexed { index, it ->
            if (job?.isActive == false) {
                return
            }

            var finishedLoading = false
            val client = Client()
            client.automaticAuthentication(false)
            client.addRelay(it)

            var count = 0
            showNotification("loading events from $it")

            fetchEventsFrom(
                client = client,
                relayUrl = it,
                until = TimeUtils.now(),
                onEvent = {
                    if (job?.isActive == false) {
                        finishedLoading = true
                    } else {
                        count++
                        if (count % 100 == 0) {
                            showNotification("loading events from $it ($count)")
                        }
                    }
                },
                onDone = {
                    Log.d(TAG, "Done loading events from $it")
                    applicationScope.launch {
                        finishedLoading = true
                        if (index == relays.size - 1) {
                            showNotification("done loading events")
                        }
                        delay(10000)
                        client.disconnect()
                    }
                },
            )
            while (!finishedLoading) {
                delay(1000)
            }
        }
    }

    suspend fun eventsToDelete() {
        if (isImportingEvents) return

        job?.cancel()
        job = applicationScope.launch(Dispatchers.IO) {
            try {
                if (Settings.deleteEphemeralEvents && isActive) {
                    Log.d(TAG, "Deleting ephemeral events")

                    val duration = measureTime {
                        AppDatabase.getNostrEphemeralDatabase().wipe()
                    }

                    Log.d(TAG, "Deleted ephemeral events $duration")
                }

                if (Settings.deleteEventsOlderThan != OlderThan.NEVER && isActive) {
                    val until = when (Settings.deleteEventsOlderThan) {
                        OlderThan.DAY -> TimeUtils.oneDayAgo()
                        OlderThan.WEEK -> TimeUtils.oneWeekAgo()
                        OlderThan.MONTH -> TimeUtils.now() - TimeUtils.ONE_MONTH
                        OlderThan.YEAR -> TimeUtils.now() - TimeUtils.ONE_YEAR
                        else -> 0
                    }
                    if (until > 0) {
                        Log.d(TAG, "Deleting old events (older than ${Settings.deleteEventsOlderThan})")
                        if (Settings.neverDeleteFrom.isNotEmpty()) {
                            AppDatabase.getNostrDatabase().query(
                                listOf(
                                    Filter()
                                        .until(Timestamp.fromSecs(until.toULong())),
                                ),
                            ).toVec().filter { it.author().toHex() !in Settings.neverDeleteFrom }.forEach {
                                AppDatabase.getNostrDatabase().delete(
                                    Filter().id(it.id()),
                                )
                            }
                        } else {
                            AppDatabase.getNostrDatabase().delete(
                                Filter()
                                    .until(Timestamp.fromSecs(until.toULong())),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error deleting events", e)
            }
        }
        job?.join()
    }

    companion object {
        const val TAG = "Citrine"

        @Volatile
        private var instance: Citrine? = null

        fun getInstance(): Citrine =
            instance ?: synchronized(this) {
                instance ?: Citrine().also { instance = it }
            }
    }
}
