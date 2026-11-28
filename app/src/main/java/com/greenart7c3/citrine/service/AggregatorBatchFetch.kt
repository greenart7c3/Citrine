package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RelayAggregator"

@Suppress("NoComments")
internal suspend fun batchFetchRelayLists(
    relayClient: RelayClient,
    pubkeys: Set<String>,
    relays: List<NormalizedRelayUrl>,
    timeoutMs: Long,
): Map<String, AdvertisedRelayListEvent> {
    if (pubkeys.isEmpty() || relays.isEmpty()) return emptyMap()
    if (!relayClient.client.isActive()) relayClient.client.connect()

    val results = ConcurrentHashMap<String, AdvertisedRelayListEvent>()
    val subId = newSubId()
    val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    val expected = relays.toSet()
    val done = CompletableDeferred<Unit>()

    val collector = object : RelayConnectionListener {
        override suspend fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            when (msg) {
                is EventMessage -> {
                    if (msg.subId != subId) return
                    val ev = msg.event as? AdvertisedRelayListEvent ?: return
                    val prev = results[ev.pubKey]
                    if (prev == null || prev.createdAt < ev.createdAt) {
                        results[ev.pubKey] = ev
                    }
                }
                is EoseMessage -> {
                    if (msg.subId != subId) return
                    if (expected.contains(relay.url)) {
                        eoseSeen.add(relay.url)
                        if (eoseSeen.size >= expected.size) {
                            done.complete(Unit)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            if (expected.contains(relay.url)) {
                eoseSeen.add(relay.url)
                if (eoseSeen.size >= expected.size) {
                    done.complete(Unit)
                }
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            if (expected.contains(relay.url)) {
                eoseSeen.add(relay.url)
                if (eoseSeen.size >= expected.size) {
                    done.complete(Unit)
                }
            }
        }
    }

    relayClient.client.addConnectionListener(collector)
    try {
        // Calling subscribe() once per chunk with the same subId would overwrite the
        // previous filter set in Quartz, leaving only the last chunk actually queried.
        val filters = pubkeys.toList().chunked(MAX_AUTHORS_PER_SUB).map { chunk ->
            Filter(
                kinds = listOf(AdvertisedRelayListEvent.KIND),
                authors = chunk,
            )
        }
        runCatching {
            relayClient.client.subscribe(subId, relays.associateWith { filters })
        }.onFailure { Log.e(TAG, "batch relay-list subscribe failed", it) }
        withTimeoutOrNull(timeoutMs) { done.await() }
    } finally {
        runCatching { relayClient.client.unsubscribe(subId) }
        relayClient.client.removeConnectionListener(collector)
    }

    results.values.forEach {
        runCatching { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
    }
    return results
}

internal suspend fun batchFetchMetadata(
    relayClient: RelayClient,
    relayToAuthors: Map<NormalizedRelayUrl, Set<String>>,
    timeoutMs: Long,
): Map<String, MetadataEvent> {
    if (relayToAuthors.isEmpty()) return emptyMap()
    if (!relayClient.client.isActive()) relayClient.client.connect()

    val results = ConcurrentHashMap<String, MetadataEvent>()
    val subId = newSubId()
    val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    val expected = relayToAuthors.keys
    val done = CompletableDeferred<Unit>()

    val collector = object : RelayConnectionListener {
        override suspend fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            when (msg) {
                is EventMessage -> {
                    if (msg.subId != subId) return
                    val ev = msg.event as? MetadataEvent ?: return
                    val prev = results[ev.pubKey]
                    if (prev == null || prev.createdAt < ev.createdAt) {
                        results[ev.pubKey] = ev
                    }
                }
                is EoseMessage -> {
                    if (msg.subId != subId) return
                    if (expected.contains(relay.url)) {
                        eoseSeen.add(relay.url)
                        if (eoseSeen.size >= expected.size) {
                            done.complete(Unit)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            if (expected.contains(relay.url)) {
                eoseSeen.add(relay.url)
                if (eoseSeen.size >= expected.size) {
                    done.complete(Unit)
                }
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            if (expected.contains(relay.url)) {
                eoseSeen.add(relay.url)
                if (eoseSeen.size >= expected.size) {
                    done.complete(Unit)
                }
            }
        }
    }

    relayClient.client.addConnectionListener(collector)
    try {
        val perRelayFilters = relayToAuthors.mapValues { (_, authors) ->
            authors.toList().chunked(MAX_AUTHORS_PER_SUB).map { chunk ->
                Filter(
                    kinds = listOf(MetadataEvent.KIND),
                    authors = chunk,
                )
            }
        }
        runCatching {
            relayClient.client.subscribe(subId, perRelayFilters)
        }.onFailure { Log.e(TAG, "batch metadata subscribe failed", it) }
        withTimeoutOrNull(timeoutMs) { done.await() }
    } finally {
        runCatching { relayClient.client.unsubscribe(subId) }
        relayClient.client.removeConnectionListener(collector)
    }

    results.values.forEach {
        runCatching { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
    }
    return results
}
