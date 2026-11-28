package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.PubkeyTimestamp
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

private const val TAG = "RelayAggregator"

private const val DEFAULT_COLD_START_WINDOW_SEC = 24L * 60L * 60L
private const val OVERLAP_SEC = 5L * 60L

private const val SQLITE_MAX_BOUND_PARAMETERS = 999
private const val AUTHORS_PER_QUERY_BESIDE_KINDS_LIST = 400

// An empty configured-kinds set means "subscribe to all kinds": pass null so the field
// is omitted from the REQ instead of serializing as "kinds":[], which relays treat as
// match-nothing.
@Suppress("NoComments")
private fun List<Int>.orNullForAllKinds(): List<Int>? = ifEmpty { null }

internal data class ChunkSubscription(
    val subId: String,
    val authors: Set<String>,
    val since: Long,
)

private class MainSubCounters {
    var reusedSubs = 0
    var freshSubs = 0
    var closedExtraChunkSubs = 0
    var coldChunks = 0
    var oldestChunkSince = Long.MAX_VALUE
    var newestChunkSince = Long.MIN_VALUE
}

internal class AggregatorSubscriptions(
    private val relayClient: RelayClient,
    private val fetcher: AggregatorFetcher,
) {
    val activeRelaySubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>> =
        ConcurrentHashMap()
    val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val lastEventByPubkey: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()

    val taggedSubIds: ConcurrentHashMap<NormalizedRelayUrl, String> = ConcurrentHashMap()

    suspend fun reseedCursors(
        dao: EventDao,
        plan: SubscriptionPlan,
        subscriptionKinds: List<Int>,
        bootstrapSince: Long,
    ) {
        val allAuthors = plan.relayToAuthors.values.flatten().toSet()
        lastEventByPubkey.keys.retainAll(plan.scopedAuthors)
        seedLastEventTimestamps(dao, allAuthors, subscriptionKinds)
        Log.d(
            TAG,
            "lastEvent map seeded: ${lastEventByPubkey.size} entries " +
                "(${allAuthors.size} authors in scope, bootstrapSince=$bootstrapSince)",
        )
    }

    fun pushMainSubs(
        relayToAuthors: Map<NormalizedRelayUrl, MutableSet<String>>,
        subscriptionKinds: List<Int>,
        bootstrapSince: Long,
    ): ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>> {
        val newActiveSubs = ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>()
        val counters = MainSubCounters()

        for ((relay, authorSet) in relayToAuthors) {
            pushRelayChunks(relay, authorSet, subscriptionKinds, bootstrapSince, newActiveSubs, counters)
        }

        var closedGoneRelaySubs = 0
        for ((relay, entries) in activeRelaySubs) {
            if (!newActiveSubs.containsKey(relay)) {
                entries.forEach { entry ->
                    runCatching { relayClient.client.unsubscribe(entry.subId) }
                    trackedSubIds.remove(entry.subId)
                    closedGoneRelaySubs++
                }
            }
        }
        val sinceSpread = if (counters.newestChunkSince >= counters.oldestChunkSince) {
            "since spread=[${counters.oldestChunkSince}..${counters.newestChunkSince}]"
        } else {
            "since spread=n/a"
        }
        Log.d(
            TAG,
            "Main subs pushed: ${counters.freshSubs} new, ${counters.reusedSubs} reused, ${counters.coldChunks} cold; " +
                "closed ${counters.closedExtraChunkSubs} shrunk chunks + $closedGoneRelaySubs dropped relays; " +
                sinceSpread,
        )
        return newActiveSubs
    }

    private fun pushRelayChunks(
        relay: NormalizedRelayUrl,
        authorSet: Set<String>,
        subscriptionKinds: List<Int>,
        bootstrapSince: Long,
        newActiveSubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>,
        counters: MainSubCounters,
    ) {
        val chunks: List<List<String>> = if (authorSet.isEmpty()) {
            listOf(emptyList())
        } else {
            chunksGroupingSimilarSince(authorSet)
        }
        val existing = activeRelaySubs[relay] ?: emptyList()
        val entries = mutableListOf<ChunkSubscription>()
        val pendingSubscribes = mutableListOf<Pair<String, List<Filter>>>()
        chunks.forEachIndexed { idx, chunk ->
            val reused = existing.getOrNull(idx)?.subId
            val subId = reused ?: newSubId()
            if (reused != null) counters.reusedSubs++ else counters.freshSubs++
            val chunkSince = computeChunkSince(chunk, bootstrapSince)
            if (chunk.isNotEmpty() && chunk.any { (lastEventByPubkey[it] ?: 0L) <= 0L }) {
                counters.coldChunks++
            }
            if (chunkSince < counters.oldestChunkSince) counters.oldestChunkSince = chunkSince
            if (chunkSince > counters.newestChunkSince) counters.newestChunkSince = chunkSince
            val filters = listOf(
                Filter(
                    authors = chunk.ifEmpty { null },
                    kinds = subscriptionKinds.orNullForAllKinds(),
                    since = chunkSince,
                ),
            )
            entries.add(
                ChunkSubscription(
                    subId = subId,
                    authors = chunk.toSet(),
                    since = chunkSince,
                ),
            )
            trackedSubIds.add(subId)
            pendingSubscribes.add(subId to filters)
        }
        publishEntriesBeforeAnyReq(relay, entries, newActiveSubs)
        pendingSubscribes.forEach { (subId, filters) -> sendSubscribe(relay, subId, filters) }
        if (existing.size > chunks.size) {
            for (i in chunks.size until existing.size) {
                val oldId = existing[i].subId
                runCatching { relayClient.client.unsubscribe(oldId) }
                trackedSubIds.remove(oldId)
                counters.closedExtraChunkSubs++
            }
        }
    }

    private fun chunksGroupingSimilarSince(authorSet: Set<String>): List<List<String>> = authorSet
        .sortedByDescending { lastEventByPubkey[it] ?: 0L }
        .chunked(MAX_AUTHORS_PER_SUB)

    private fun publishEntriesBeforeAnyReq(
        relay: NormalizedRelayUrl,
        entries: MutableList<ChunkSubscription>,
        newActiveSubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>,
    ) {
        newActiveSubs[relay] = entries
        activeRelaySubs[relay] = entries.toMutableList()
    }

    private fun publishTaggedEntryBeforeReq(relay: NormalizedRelayUrl, taggedEntry: ChunkSubscription) {
        activeRelaySubs.compute(relay) { _, current ->
            val list = current?.let { ArrayList(it) } ?: ArrayList()
            list.add(taggedEntry)
            list
        }
    }

    fun pushTaggedSubs(
        newActiveSubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>,
        aggPubkey: String,
        aggRelayList: AdvertisedRelayListEvent?,
        subscriptionKinds: List<Int>,
        bootstrapSince: Long,
    ) {
        val readRelays = aggRelayList?.readRelays()
            ?.mapNotNull { raw -> fetcher.normalizeRemote(raw) }
            ?: emptyList()
        val taggedSubRelays = (readRelays + FALLBACK_OUTBOX_RELAYS).toSet()
        val filters = listOf(
            Filter(
                tags = mapOf("p" to listOf(aggPubkey)),
                kinds = subscriptionKinds.orNullForAllKinds(),
                since = bootstrapSince,
            ),
        )
        var freshTagged = 0
        var reusedTagged = 0
        for (relay in taggedSubRelays) {
            val existingId = taggedSubIds[relay]
            val id = existingId ?: newSubId().also { taggedSubIds[relay] = it }
            if (existingId != null) reusedTagged++ else freshTagged++
            trackedSubIds.add(id)
            val taggedEntry = ChunkSubscription(
                subId = id,
                authors = emptySet(),
                since = bootstrapSince,
            )
            newActiveSubs.getOrPut(relay) { mutableListOf() }.add(taggedEntry)
            publishTaggedEntryBeforeReq(relay, taggedEntry)
            runCatching {
                relayClient.client.subscribe(id, mapOf(relay to filters))
            }.onFailure { Log.e(TAG, "tagged subscribe to ${relay.url} failed", it) }
        }
        val staleTaggedRelays = taggedSubIds.keys - taggedSubRelays
        for (relay in staleTaggedRelays) {
            val oldId = taggedSubIds.remove(relay) ?: continue
            runCatching { relayClient.client.unsubscribe(oldId) }
            trackedSubIds.remove(oldId)
        }
        Log.d(
            TAG,
            "Tagged subs: $freshTagged new, $reusedTagged reused across " +
                "${taggedSubRelays.size} relays (${readRelays.size} inbox, " +
                "${FALLBACK_OUTBOX_RELAYS.size} fallback); dropped ${staleTaggedRelays.size}",
        )
    }

    fun dropTaggedSubs() {
        if (taggedSubIds.isNotEmpty()) {
            Log.d(TAG, "Tagged sub disabled, unsubscribing ${taggedSubIds.size} relay subs")
            taggedSubIds.forEach { (_, id) ->
                runCatching { relayClient.client.unsubscribe(id) }
                trackedSubIds.remove(id)
            }
            taggedSubIds.clear()
        }
    }

    fun commit(newActiveSubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>): Set<NormalizedRelayUrl> {
        val staleRelays = activeRelaySubs.keys.toSet() - newActiveSubs.keys
        staleRelays.forEach { activeRelaySubs.remove(it) }
        newActiveSubs.forEach { (relay, entries) -> activeRelaySubs[relay] = entries }

        val newSubscribedRelays = newActiveSubs.keys
        subscribedRelays.clear()
        subscribedRelays.addAll(newSubscribedRelays)
        connectedRelays.retainAll(newSubscribedRelays)
        return newSubscribedRelays
    }

    fun filterMismatchReason(relayUrl: NormalizedRelayUrl, subId: String, ev: Event): String? {
        val entries = activeRelaySubs[relayUrl] ?: return "unknown relay (no active subs)"
        val entry = entries.firstOrNull { it.subId == subId } ?: return "unknown subId"
        return kindOrSinceMismatch(entry, ev) ?: authorOrTagMismatch(entry, subId, ev)
    }

    private fun kindOrSinceMismatch(entry: ChunkSubscription, ev: Event): String? {
        val kinds = Settings.relayAggregatorKinds
        if (kinds.isNotEmpty() && ev.kind !in kinds) {
            return "kind ${ev.kind} not in requested kinds $kinds"
        }

        if (ev.createdAt < entry.since) {
            return "createdAt ${ev.createdAt} < requested since ${entry.since}"
        }
        return null
    }

    private fun authorOrTagMismatch(entry: ChunkSubscription, subId: String, ev: Event): String? = if (entry.authors.isNotEmpty()) {
        if (ev.pubKey !in entry.authors) {
            "pubkey ${ev.pubKey} not in requested authors (${entry.authors.size})"
        } else {
            null
        }
    } else if (taggedSubIds.containsValue(subId)) {
        taggedMismatch(ev)
    } else {
        null
    }

    private fun taggedMismatch(ev: Event): String? {
        val aggPubkey = Settings.aggregatorPubkey
        if (aggPubkey.isBlank()) return "tagged sub active but aggregator pubkey is blank"
        val tagged = ev.tags.any { it.size > 1 && it[0] == "p" && it[1] == aggPubkey }
        return if (!tagged) "missing required p-tag for $aggPubkey" else null
    }

    private fun sendSubscribe(relay: NormalizedRelayUrl, subId: String, filters: List<Filter>) {
        runCatching {
            relayClient.client.subscribe(subId, mapOf(relay to filters))
        }.onFailure { Log.e(TAG, "subscribe to ${relay.url} failed", it) }
    }

    fun computeBootstrapSince(): Long {
        val now = TimeUtils.now()
        val last = Settings.relayAggregatorLastSync
        return if (last <= 0L) {
            now - DEFAULT_COLD_START_WINDOW_SEC
        } else {
            maxOf(last - OVERLAP_SEC, now - DEFAULT_COLD_START_WINDOW_SEC)
        }
    }

    private fun computeChunkSince(chunk: List<String>, fallback: Long): Long {
        if (chunk.isEmpty()) return fallback
        val now = TimeUtils.now()
        val coldStart = now - DEFAULT_COLD_START_WINDOW_SEC
        var minSeen = Long.MAX_VALUE
        for (pk in chunk) {
            val seen = lastEventByPubkey[pk] ?: 0L
            if (seen <= 0L) return coldStart
            if (seen < minSeen) minSeen = seen
        }
        return maxOf(minSeen - OVERLAP_SEC, coldStart)
    }

    private suspend fun seedLastEventTimestamps(
        dao: EventDao,
        authors: Set<String>,
        kinds: List<Int>,
    ) {
        if (authors.isEmpty()) return
        if (kinds.isEmpty()) {
            seedLastEventTimestampsAllKinds(dao, authors)
            return
        }
        val authorChunks = authors.toList().chunked(AUTHORS_PER_QUERY_BESIDE_KINDS_LIST)
        val rows = mutableListOf<PubkeyTimestamp>()
        for (chunk in authorChunks) {
            try {
                rows.addAll(dao.getLatestEventTimestamps(chunk, kinds))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "seedLastEventTimestamps chunk failed", e)
            }
        }
        rows.forEach { row -> keepNewerCursor(row) }
    }

    private fun keepNewerCursor(row: PubkeyTimestamp) {
        lastEventByPubkey.merge(row.pubkey, row.createdAt) { a, b -> maxOf(a, b) }
    }

    private suspend fun seedLastEventTimestampsAllKinds(
        dao: EventDao,
        authors: Set<String>,
    ) {
        val rows = mutableListOf<PubkeyTimestamp>()
        for (chunk in authors.toList().chunked(SQLITE_MAX_BOUND_PARAMETERS)) {
            try {
                rows.addAll(dao.getLatestEventTimestampsAllKinds(chunk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "seedLastEventTimestampsAllKinds chunk failed", e)
            }
        }
        rows.forEach { row -> keepNewerCursor(row) }
    }
}
