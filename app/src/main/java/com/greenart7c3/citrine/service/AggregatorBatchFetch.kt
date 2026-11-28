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

/**
 * Issues one subscription to all [relays] asking for kind-10002 events authored by any
 * pubkey in [pubkeys] and collects the latest per author. Returns as soon as every relay
 * sends EOSE or after [timeoutMs] milliseconds, whichever comes first.
 */
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
        // Pack every author chunk into a single REQ as multiple filters.
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

    // Persist what we learned so future refreshes are a cache hit.
    results.values.forEach {
        runCatching { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
    }
    return results
}

/**
 * Issues a single subscription that asks each relay in [relayToAuthors] for kind-0 events
 * authored by the pubkeys mapped to it (no `since`), then collects the latest per author.
 * The per-relay routing lets the caller send each author's metadata fetch to their own
 * NIP-65 write relays when known, falling back to indexer relays otherwise. Returns when
 * every relay sends EOSE or after [timeoutMs] elapses.
 */
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
        // One filter list per relay so each relay only gets asked about the authors that
        // actually advertised it as a write relay. Authors with no NIP-65 are routed to
        // the indexer relays by the caller.
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
