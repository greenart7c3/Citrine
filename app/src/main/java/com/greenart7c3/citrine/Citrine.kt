package com.greenart7c3.citrine

import android.app.Application
import android.util.Log
import androidx.compose.runtime.MutableState
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.Client
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.RelayPool
import com.vitorpamplona.ammolite.relays.TypedFilter
import com.vitorpamplona.ammolite.relays.filters.SincePerRelayFilter
import com.vitorpamplona.quartz.encoders.HexKey
import com.vitorpamplona.quartz.events.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.EventInterface
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.UUID
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Citrine : Application() {
    override fun onCreate() {
        super.onCreate()

        instance = this
        LocalPreferences.loadSettingsFromEncryptedStorage(this)
    }

    suspend fun fetchContactList(
        pubKey: HexKey,
        onDone: () -> Unit,
    ) {
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    Client.sendAndWaitForResponse(event, "ws://127.0.0.1:${Settings.port}")
                }
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
                                authors = listOf(pubKey),
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
        onDone()
    }

    suspend fun fetchOutbox(
        pubKey: HexKey,
        onDone: () -> Unit,
    ) {
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    Client.sendAndWaitForResponse(event, "ws://127.0.0.1:${Settings.port}")
                }
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
                                authors = listOf(pubKey),
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
        onDone()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun fetchEventsFrom(
        relayParam: Relay,
        pubKey: HexKey,
        until: Long,
        onDone: () -> Unit,
    ) {
        val subId = UUID.randomUUID().toString().substring(0, 4)
        val eventCount = mutableMapOf<String, Int>()
        var lastEventCreatedAt = until
        val listeners = mutableMapOf<String, Relay.Listener>()
        val listener = RelayListener2(
            onReceiveEvent = { relay, _, event ->
                eventCount[relay.url] = eventCount[relay.url]?.plus(1) ?: 1
                lastEventCreatedAt = event.createdAt
                runBlocking {
                    Client.sendAndWaitForResponse(event, "ws://127.0.0.1:${Settings.port}")
                }
            },
            onEOSE = { relay ->
                Log.d(TAG, "Received EOSE from relay ${relay.url}")
                relay.close(subId)
                listeners[relay.url]?.let {
                    relayParam.unregister(it)
                }
                GlobalScope.launch(Dispatchers.IO) {
                    if ((eventCount[relay.url] ?: 0) > 0) {
                        if (lastEventCreatedAt == until) {
                            onDone()
                        } else {
                            fetchEventsFrom(relay, pubKey, lastEventCreatedAt, onDone)
                        }
                    } else {
                        onDone()
                    }
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
        )

        eventCount[relayParam.url] = 0
        relayParam.register(listener)
        listeners[relayParam.url] = listener
        if (!relayParam.isConnected()) {
            relayParam.connectAndRun { relay ->
                relay.sendFilter(
                    subId,
                    filters = listOf(
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                authors = listOf(pubKey),
                                until = until,
                            ),
                        ),
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                kinds = listOf(AdvertisedRelayListEvent.KIND),
                                tags = mapOf("a" to listOf(pubKey)),
                                until = until,
                            ),
                        ),
                    ),
                )
            }
        } else {
            relayParam.sendFilter(
                subId,
                filters = listOf(
                    TypedFilter(
                        types = COMMON_FEED_TYPES,
                        filter = SincePerRelayFilter(
                            authors = listOf(pubKey),
                            until = until,
                        ),
                    ),
                    TypedFilter(
                        types = COMMON_FEED_TYPES,
                        filter = SincePerRelayFilter(
                            kinds = listOf(AdvertisedRelayListEvent.KIND),
                            tags = mapOf("a" to listOf(pubKey)),
                            until = until,
                        ),
                    ),
                ),
            )
        }
    }

    suspend fun fetchEvents(
        pubKey: HexKey,
        isLoading: MutableState<Boolean>,
        progress: MutableState<String>,
    ) {
        val finishedLoading = mutableMapOf<String, Boolean>()
        RelayPool.getAll().filter { !it.url.contains("127.0.0.1") }.forEach {
            finishedLoading[it.url] = false
        }

        RelayPool.getAll().forEach {
            progress.value = "loading events from ${it.url}"
            fetchEventsFrom(
                relayParam = it,
                pubKey = pubKey,
                until = TimeUtils.now(),
                onDone = {
                    finishedLoading[it.url] = true
                },
            )
            while (finishedLoading[it.url] == false) {
                delay(1000)
            }
        }

        RelayPool.disconnect()
        RelayPool.unloadRelays()
        delay(5000)
        isLoading.value = false
        progress.value = ""
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

class RelayListener(
    val onReceiveEvent: (relay: Relay, subscriptionId: String, event: Event) -> Unit,
) : Relay.Listener {
    override fun onAuth(relay: Relay, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
    }

    override fun onBeforeSend(relay: Relay, event: EventInterface) {
        Log.d("RelayListener", "Sending event ${event.id()} to relay ${relay.url}")
    }

    override fun onError(relay: Relay, subscriptionId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subscriptionId")
    }

    override fun onEvent(relay: Relay, subscriptionId: String, event: Event, afterEOSE: Boolean) {
        Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subscriptionId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subscriptionId, event)
    }

    override fun onNotify(relay: Relay, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: Relay, type: Relay.StateType, channel: String?) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
    }

    override fun onSend(relay: Relay, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: Relay, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}

class RelayListener2(
    val onReceiveEvent: (relay: Relay, subscriptionId: String, event: Event) -> Unit,
    val onEOSE: (relay: Relay) -> Unit,
    val onErrorFunc: (relay: Relay, subscriptionId: String, error: Error) -> Unit,
) : Relay.Listener {
    override fun onAuth(relay: Relay, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
        onEOSE(relay)
    }

    override fun onBeforeSend(relay: Relay, event: EventInterface) {
        Log.d("RelayListener", "Sending event ${event.id()} to relay ${relay.url}")
    }

    override fun onError(relay: Relay, subscriptionId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subscriptionId")
        onErrorFunc(relay, subscriptionId, error)
    }

    override fun onEvent(relay: Relay, subscriptionId: String, event: Event, afterEOSE: Boolean) {
        // Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subscriptionId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subscriptionId, event)
    }

    override fun onNotify(relay: Relay, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: Relay, type: Relay.StateType, channel: String?) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
        if (type == Relay.StateType.EOSE) {
            onEOSE(relay)
        }
    }

    override fun onSend(relay: Relay, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: Relay, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}
