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
import com.greenart7c3.citrine.RelayListener
import com.greenart7c3.citrine.RelayListener2
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.MutableSubscriptionCache
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.TypedFilter
import com.vitorpamplona.ammolite.relays.filters.SincePerRelayFilter
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.LinkedList
import java.util.UUID
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EventsDownloaderCache(val maxSize: Int = 200) {
    private val cache: LinkedList<String> = LinkedList()

    fun contains(element: String): Boolean {
        return cache.contains(element)
    }

    fun addElement(element: String) {
        // Add the new element
        cache.addLast(element)

        // If the cache exceeds the max size, remove the oldest element
        if (cache.size > maxSize) {
            cache.removeFirst()
        }
    }

    fun clearCache() {
        cache.clear()
    }
}

object EventDownloader {
    const val LIMIT = 500
    val eventCache = EventsDownloaderCache()

    suspend fun fetchAdvertisedRelayList(
        signer: NostrSigner,
    ): AdvertisedRelayListEvent? {
        var result: AdvertisedRelayListEvent? = null
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                subs = MutableSubscriptionCache(),
                socketBuilderFactory = Citrine.getInstance().factory,
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                subs = MutableSubscriptionCache(),
                socketBuilderFactory = Citrine.getInstance().factory,
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
                }
                result = event as AdvertisedRelayListEvent
                finishedRelays[relay.url] = true
            },
        )

        relays.forEach {
            it.register(listener)
            it.connectAndRunAfterSync {
                it.sendFilter(
                    UUID.randomUUID().toString().substring(0, 4),
                    filters = listOf(
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                kinds = listOf(AdvertisedRelayListEvent.KIND),
                                authors = listOf(signer.pubKey),
                                limit = 1,
                            ),
                        ),
                    ),
                )
            }
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
        }
        relays.forEach {
            it.unregister(listener)
        }
        return result
    }

    suspend fun fetchContactList(
        signer: NostrSigner,
    ): ContactListEvent? {
        var result: ContactListEvent? = null
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                socketBuilderFactory = Citrine.getInstance().factory,
                subs = MutableSubscriptionCache(),
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                socketBuilderFactory = Citrine.getInstance().factory,
                subs = MutableSubscriptionCache(),
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
                }
                result = event as ContactListEvent
                finishedRelays[relay.url] = true
            },
        )

        relays.forEach {
            it.register(listener)
            it.connectAndRunAfterSync {
                it.sendFilter(
                    UUID.randomUUID().toString().substring(0, 4),
                    filters = listOf(
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                kinds = listOf(ContactListEvent.KIND),
                                authors = listOf(signer.pubKey),
                                limit = 1,
                            ),
                        ),
                    ),
                )
            }
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
        }
        relays.forEach {
            it.unregister(listener)
        }
        return result
    }

    suspend fun fetchEvents(
        signer: NostrSigner,
        onAuth: (relay: Relay, challenge: String, subId: String) -> Unit,
        scope: CoroutineScope,
    ) {
        try {
            val finishedLoading = mutableMapOf<String, Boolean>()

            Citrine.getInstance().client.getAll().filter { !it.url.contains("127.0.0.1") }.forEach {
                finishedLoading[it.url] = false
            }

            Citrine.getInstance().client.getAll().forEach {
                var count = 0
                setProgress("loading events from ${it.url}")
                val listeners = mutableMapOf<String, Relay.Listener>()

                fetchEventsFrom(
                    scope = scope,
                    signer = signer,
                    listeners = listeners,
                    relayParam = it,
                    until = TimeUtils.now(),
                    onAuth = { relay, challenge, subId ->
                        onAuth(relay, challenge, subId)
                    },
                    onEvent = {
                        count++
                        if (count % LIMIT == 0) {
                            setProgress("loading events from ${it.url} ($count)")
                        }
                    },
                    onDone = {
                        finishedLoading[it.url] = true
                    },
                )
                var timeElapsed = 0
                while (finishedLoading[it.url] == false) {
                    delay(1000)
                    timeElapsed++
                    if (timeElapsed > 60 && count == 0) {
                        break
                    }
                }
                Log.d(Citrine.TAG, "Finished inserting events from ${it.url}")
                eventCache.clearCache()
                if (it.isConnected()) {
                    it.disconnect()
                }
            }

            Citrine.getInstance().client.getAll().forEach {
                it.disconnect()
            }

            delay(5000)

            Citrine.getInstance().client.reconnect(relays = null)

            setProgress("Finished loading events from ${Citrine.getInstance().client.getAll().size} relays")
            Citrine.isImportingEvents = false
        } catch (e: Exception) {
            Citrine.isImportingEvents = false
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, e.message ?: "", e)
            setProgress("Failed to load events")
        }
    }

    suspend fun saveEvents(events: List<Event>, scope: CoroutineScope) {
        val jobs = mutableListOf<Deferred<Unit?>>()
        events.forEach {
            jobs.add(
                scope.async {
                    CustomWebSocketService.server?.innerProcessEvent(it, null)
                },
            )
        }
        jobs.awaitAll()
    }

    fun fetchEventsFrom(
        scope: CoroutineScope,
        signer: NostrSigner,
        listeners: MutableMap<String, Relay.Listener>,
        relayParam: Relay,
        until: Long,
        onEvent: () -> Unit,
        onAuth: (relay: Relay, challenge: String, subId: String) -> Unit,
        onDone: () -> Unit,
    ) {
        val subId = UUID.randomUUID().toString().substring(0, 4)
        val eventCount = mutableMapOf<String, Int>()
        var lastEventCreatedAt = until

        listeners[relayParam.url]?.let {
            relayParam.unregister(it)
        }
        val events = mutableListOf<Event>()

        val listener = RelayListener2(
            onReceiveEvent = { relay, _, event ->
                onEvent()
                if (!eventCache.contains(event.id)) {
                    eventCache.addElement(event.id)
                    eventCount[relay.url] = eventCount[relay.url]?.plus(1) ?: 1
                    lastEventCreatedAt = if (event.createdAt < lastEventCreatedAt) {
                        event.createdAt - 1
                    } else {
                        lastEventCreatedAt - 1
                    }
                    if (event.pubKey == signer.pubKey) {
                        Log.d(Citrine.TAG, "Received event ${event.id} from relay ${relay.url}")
                        events.add(event)

//                        scope.launch {
//                            CustomWebSocketService.server?.innerProcessEvent(event, null)
//                        }
                    }
                } else {
                    lastEventCreatedAt = lastEventCreatedAt - 1
                    Log.d(Citrine.TAG, "Received duplicate event ${event.id} from relay ${relay.url}")
                }
            },
            onEOSE = { relay ->
                Log.d(Citrine.TAG, "Received EOSE from relay ${relay.url}")

                scope.launch {
                    val time = measureTime {
                        saveEvents(events, scope)
                    }
                    Log.d(Citrine.TAG, "Saved ${events.size} events in $time")
                    relay.close(subId)
                    listeners[relay.url]?.let {
                        relayParam.unregister(it)
                    }

                    val localEventCount = eventCount[relay.url] ?: 0
                    if (localEventCount < LIMIT) {
                        onDone()
                    } else {
                        val localUntil = if (lastEventCreatedAt == until) {
                            Log.d("filter", "until is the same as lastEventCreatedAt")
                            until - 1
                        } else {
                            lastEventCreatedAt
                        }
                        fetchEventsFrom(scope, signer, listeners, relay, localUntil, onEvent, onAuth, onDone)
                    }
                }
            },
            onErrorFunc = { relay, sub, error ->
                if (error.message?.contains("Socket closed") == false) {
                    scope.launch {
                        saveEvents(events, scope)
                        if (relay.isConnected()) {
                            relay.close(sub)
                        }
                        listeners[relay.url]?.let {
                            relayParam.unregister(it)
                        }
                        onDone()
                    }
                }
            },
            onAuthFunc = { relay, challenge ->
                Log.d(Citrine.TAG, "Received auth from relay ${relay.url}")
                onAuth(relay, challenge, subId)
            },
        )

        Log.d("filter", lastEventCreatedAt.toString())

        val filters = listOf(
            TypedFilter(
                types = COMMON_FEED_TYPES,
                filter = SincePerRelayFilter(
                    authors = listOf(signer.pubKey),
                    until = lastEventCreatedAt,
                    limit = LIMIT,
                ),
            ),
        )

        eventCount[relayParam.url] = 0
        relayParam.register(listener)
        listeners[relayParam.url] = listener
        if (!relayParam.isConnected()) {
            relayParam.connectAndRunAfterSync {
                relayParam.sendFilter(
                    subId,
                    filters = filters,
                )
            }
        } else {
            relayParam.sendFilter(
                subId,
                filters = filters,
            )
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
