package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

private const val TAG = "RelayAggregator"

private const val MAX_RELAYS_PER_AUTHOR = 3

private const val MAX_TOTAL_SUB_RELAYS = 200

internal class RelayLists(
    private val cachedRelayLists: Map<String, AdvertisedRelayListEvent>,
    private val fetched: Map<String, AdvertisedRelayListEvent>,
) {
    fun of(pubkey: String): AdvertisedRelayListEvent? = cachedRelayLists[pubkey] ?: fetched[pubkey]
}

internal class SubscriptionPlan(
    val relayToAuthors: MutableMap<NormalizedRelayUrl, MutableSet<String>>,
    val relayLists: RelayLists,
    val scopedAuthors: Set<String>,
    val ownerFollows: Set<String>,
)

internal suspend fun resolveRelayLists(
    relayClient: RelayClient,
    fetcher: AggregatorFetcher,
    dao: EventDao,
    authors: Set<String>,
    indexers: List<NormalizedRelayUrl>,
): RelayLists {
    val cachedRelayLists = mutableMapOf<String, AdvertisedRelayListEvent>()
    val uncached = mutableSetOf<String>()
    for (pk in authors) {
        val cached = fetcher.loadCleanLatestByKind(dao, pk, AdvertisedRelayListEvent.KIND)?.toEvent() as? AdvertisedRelayListEvent
        if (cached != null) cachedRelayLists[pk] = cached else uncached.add(pk)
    }
    Log.d(TAG, "NIP-65 cache: ${cachedRelayLists.size} hit, ${uncached.size} miss")

    val fetchMs = System.currentTimeMillis()
    val fetched = if (uncached.isNotEmpty()) {
        Log.d(TAG, "Fetching ${uncached.size} NIP-65 records from ${indexers.size} indexers")
        batchFetchRelayLists(relayClient, uncached, indexers, BATCH_FETCH_TIMEOUT_MS)
    } else {
        emptyMap()
    }
    if (uncached.isNotEmpty()) {
        Log.d(
            TAG,
            "NIP-65 batch fetch returned ${fetched.size}/${uncached.size} lists " +
                "in ${System.currentTimeMillis() - fetchMs}ms",
        )
    }
    return RelayLists(cachedRelayLists, fetched)
}

internal fun buildOutboxMap(
    fetcher: AggregatorFetcher,
    authors: Set<String>,
    relayLists: RelayLists,
    sources: List<NormalizedRelayUrl>,
): MutableMap<NormalizedRelayUrl, MutableSet<String>> {
    val relayToAuthors = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
    val authorWriteRelays = mutableMapOf<String, List<NormalizedRelayUrl>>()
    var fellBackCount = 0
    for (pk in authors) {
        val relayListEvent = relayLists.of(pk)
        authorWriteRelays[pk] = relayListEvent
            ?.writeRelays()
            ?.mapNotNull { raw -> fetcher.normalizeRemote(raw) }
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                fellBackCount++
                FALLBACK_OUTBOX_RELAYS
            }
    }
    val capped = capRelaysPerAuthorPreferringShared(authorWriteRelays, MAX_RELAYS_PER_AUTHOR)
    relayToAuthors.putAll(capped)
    sources.forEach { relay ->
        relayToAuthors.getOrPut(relay) { mutableSetOf() }.addAll(authors)
    }
    if (relayToAuthors.size > MAX_TOTAL_SUB_RELAYS) {
        val keptKeys = relayToAuthors.entries
            .sortedWith(
                compareByDescending<Map.Entry<NormalizedRelayUrl, MutableSet<String>>> { it.value.size }
                    .thenComparator { a, b -> a.key.url.compareTo(b.key.url) },
            )
            .take(MAX_TOTAL_SUB_RELAYS)
            .map { it.key }
            .toSet()
        val droppedCount = relayToAuthors.size - keptKeys.size
        relayToAuthors.keys.retainAll(keptKeys)
        Log.d(
            TAG,
            "Relay cap: dropped $droppedCount relays beyond $MAX_TOTAL_SUB_RELAYS " +
                "(kept the ones covering the most authors)",
        )
    }
    Log.d(
        TAG,
        "Outbox map built: ${relayToAuthors.size} relays for ${authors.size} authors " +
            "(cap=$MAX_RELAYS_PER_AUTHOR/author, ${sources.size} aggregator source(s), " +
            "$fellBackCount author(s) had no NIP-65, fell back to ${FALLBACK_OUTBOX_RELAYS.size} relays)",
    )
    return relayToAuthors
}

internal suspend fun fetchOwnerMetadata(
    relayClient: RelayClient,
    fetcher: AggregatorFetcher,
    authors: Set<String>,
    relayLists: RelayLists,
    indexers: List<NormalizedRelayUrl>,
    sources: List<NormalizedRelayUrl>,
) {
    val authorMetadataRelays = mutableMapOf<String, List<NormalizedRelayUrl>>()
    var metadataIndexerFallback = 0
    for (pk in authors) {
        val relayListEvent = relayLists.of(pk)
        authorMetadataRelays[pk] = relayListEvent
            ?.writeRelays()
            ?.mapNotNull { raw -> fetcher.normalizeRemote(raw) }
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                metadataIndexerFallback++
                indexers
            }
    }
    val metadataRelayToAuthors = capRelaysPerAuthorPreferringShared(authorMetadataRelays, MAX_RELAYS_PER_AUTHOR)
    sources.forEach { relay ->
        metadataRelayToAuthors.getOrPut(relay) { mutableSetOf() }.addAll(authors)
    }
    val metadataMs = System.currentTimeMillis()
    val metadataFetched = batchFetchMetadata(relayClient, metadataRelayToAuthors, BATCH_FETCH_TIMEOUT_MS)
    Log.d(
        TAG,
        "Metadata batch fetch returned ${metadataFetched.size}/${authors.size} " +
            "from ${metadataRelayToAuthors.size} relays " +
            "(cap=$MAX_RELAYS_PER_AUTHOR/author, ${sources.size} aggregator source(s), " +
            "$metadataIndexerFallback author(s) had no NIP-65, fell back to indexers) " +
            "in ${System.currentTimeMillis() - metadataMs}ms",
    )
}

private fun capRelaysPerAuthorPreferringShared(
    authorWriteRelays: Map<String, List<NormalizedRelayUrl>>,
    cap: Int,
): MutableMap<NormalizedRelayUrl, MutableSet<String>> {
    val relayPopularity = mutableMapOf<NormalizedRelayUrl, Int>()
    for ((_, relays) in authorWriteRelays) {
        for (r in relays.distinct()) relayPopularity.merge(r, 1) { a, b -> a + b }
    }
    val result = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
    for ((pk, relays) in authorWriteRelays) {
        val chosen = relays
            .distinct()
            .sortedByDescending { relayPopularity[it] ?: 0 }
            .take(cap)
        chosen.forEach { r -> result.getOrPut(r) { mutableSetOf() }.add(pk) }
    }
    return result
}
