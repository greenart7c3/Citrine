package com.greenart7c3.citrine.service

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.RelayListener
import com.greenart7c3.citrine.okhttp.OkHttpWebSocket
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.MutableSubscriptionManager
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.TypedFilter
import com.vitorpamplona.ammolite.relays.filters.SincePerRelayFilter
import com.vitorpamplona.quartz.events.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.signers.NostrSigner
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

object EventDownloader {
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
}
