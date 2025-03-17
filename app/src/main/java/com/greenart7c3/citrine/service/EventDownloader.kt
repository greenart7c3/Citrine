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
import com.greenart7c3.citrine.okhttp.OkHttpWebSocket
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.MutableSubscriptionManager
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.TypedFilter
import com.vitorpamplona.ammolite.relays.filters.SincePerRelayFilter
import com.vitorpamplona.quartz.events.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.signers.NostrSigner
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object EventDownloader {
    const val LIMIT = 300

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
                subs = MutableSubscriptionManager(),
                socketBuilder = OkHttpWebSocket.Builder(),
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                subs = MutableSubscriptionManager(),
                socketBuilder = OkHttpWebSocket.Builder(),
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
            it.connectAndRun { relay ->
                relay.sendFilter(
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
                socketBuilder = OkHttpWebSocket.Builder(),
                subs = MutableSubscriptionManager(),
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
                socketBuilder = OkHttpWebSocket.Builder(),
                subs = MutableSubscriptionManager(),
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
            it.connectAndRun { relay ->
                relay.sendFilter(
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
        onAuth: (relay: Relay, challenge: String) -> Unit,
        scope: CoroutineScope,
    ) {
        val finishedLoading = mutableMapOf<String, Boolean>()

        Citrine.getInstance().client.getAll().filter { !it.url.contains("127.0.0.1") }.forEach {
            finishedLoading[it.url] = false
        }

        Citrine.getInstance().client.getAll().forEach {
            var count = 0
            setProgress("loading events from ${it.url}")
            val listeners = mutableMapOf<String, Relay.Listener>()

            val channel = Channel<Event>(
                capacity = 300,
                onUndeliveredElement = {
                    Log.d(Citrine.TAG, "Undelivered element: $it")
                },
            )

            Citrine.getInstance().applicationScope.launch {
                val eventList = mutableListOf<Event>()

                for (message in channel) {
                    eventList.add(message)
                    if (eventList.size >= LIMIT) {
                        Log.d(Citrine.TAG, "Events to insert inner: ${eventList.size}")
                        for (event in eventList) {
                            CustomWebSocketService.server?.innerProcessEvent(event, null)
                        }
                        eventList.clear()
                    }
                }
                if (eventList.isNotEmpty()) Log.d(Citrine.TAG, "Events to insert: ${eventList.size}")
                for (event in eventList) {
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
                }
            }

            fetchEventsFrom(
                signer = signer,
                channel = channel,
                listeners = listeners,
                relayParam = it,
                until = TimeUtils.now(),
                onAuth = { relay, challenge ->
                    onAuth(relay, challenge)
                    scope.launch(Dispatchers.IO) {
                        delay(5000)

                        fetchEventsFrom(
                            signer = signer,
                            channel = channel,
                            listeners = listeners,
                            relayParam = it,
                            until = TimeUtils.now(),
                            onAuth = { _, _ ->
                                finishedLoading[it.url] = true
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
                    }
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
            channel.close()
            if (it.isConnected()) {
                it.disconnect()
            }
        }

        Citrine.getInstance().client.getAll().forEach {
            it.disconnect()
        }

        delay(5000)

        setProgress("Finished loading events from ${Citrine.getInstance().client.getAll().size} relays")

        Citrine.getInstance().isImportingEvents = false
    }

    fun fetchEventsFrom(
        signer: NostrSigner,
        channel: Channel<Event>,
        listeners: MutableMap<String, Relay.Listener>,
        relayParam: Relay,
        until: Long,
        onEvent: () -> Unit,
        onAuth: (relay: Relay, challenge: String) -> Unit,
        onDone: () -> Unit,
    ) {
        val subId = UUID.randomUUID().toString().substring(0, 4)
        val eventCount = mutableMapOf<String, Int>()
        var lastEventCreatedAt = until

        listeners[relayParam.url]?.let {
            relayParam.unregister(it)
        }

        val listener = RelayListener2(
            onReceiveEvent = { relay, _, event ->
                onEvent()
                eventCount[relay.url] = eventCount[relay.url]?.plus(1) ?: 1
                lastEventCreatedAt = event.createdAt

                var result = channel.trySend(event)
                while (result.isFailure) {
                    result = channel.trySend(event)
                }
                if (result.isFailure) {
                    Log.e(Citrine.TAG, "Failed to send event to channel")
                }
            },
            onEOSE = { relay ->
                Log.d(Citrine.TAG, "Received EOSE from relay ${relay.url}")
                relay.close(subId)
                listeners[relay.url]?.let {
                    relayParam.unregister(it)
                }

                val localEventCount = eventCount[relay.url] ?: 0
                if (localEventCount < LIMIT) {
                    onDone()
                } else {
                    fetchEventsFrom(signer, channel, listeners, relay, lastEventCreatedAt, onEvent, onAuth, onDone)
                }
            },
            onErrorFunc = { relay, sub, error ->
                if (error.message?.contains("Socket closed") == false) {
                    if (relay.isConnected()) {
                        relay.close(sub)
                    }
                    listeners[relay.url]?.let {
                        relayParam.unregister(it)
                    }
                    onDone()
                }
            },
            onAuthFunc = { relay, challenge ->
                onAuth(relay, challenge)
            },
        )

        val filters = listOf(
            TypedFilter(
                types = COMMON_FEED_TYPES,
                filter = SincePerRelayFilter(
                    authors = listOf(signer.pubKey),
                    until = until,
                    limit = LIMIT,
                ),
            ),
        )

        eventCount[relayParam.url] = 0
        relayParam.register(listener)
        listeners[relayParam.url] = listener
        if (!relayParam.isConnected()) {
            relayParam.connectAndRun { relay ->
                relay.sendFilter(
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
