package com.greenart7c3.citrine.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.PubkeyTimestamp
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EoseMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AggregatorPhase {
    IDLE,
    BOOTSTRAPPING,
    REFRESHING,
    LISTENING,
    PAUSED,
}

data class AggregatorStatus(
    val enabled: Boolean = false,
    val phase: AggregatorPhase = AggregatorPhase.IDLE,
    val authors: Int = 0,
    val relaysConfigured: Int = 0,
    val relaysConnected: Int = 0,
    val eventsReceived: Long = 0L,
    val lastRefreshEpoch: Long = 0L,
)

private data class ChunkSubscription(
    val subId: String,
    val authors: Set<String>,
    val since: Long,
)

object RelayAggregator {
    private const val TAG = "RelayAggregator"

    private val INDEXER_RELAYS = listOf(
        RelayUrlNormalizer.normalize("wss://purplepag.es/"),
        RelayUrlNormalizer.normalize("wss://user.kindpag.es/"),
        RelayUrlNormalizer.normalize("wss://profiles.nostr1.com/"),
        RelayUrlNormalizer.normalize("wss://directory.yabu.me/"),
    )

    private val FALLBACK_OUTBOX_RELAYS = listOf(
        RelayUrlNormalizer.normalize("wss://relay.damus.io/"),
        RelayUrlNormalizer.normalize("wss://nos.lol/"),
        RelayUrlNormalizer.normalize("wss://relay.primal.net/"),
    )

    // Keep well under the per-filter author cap that stricter relays enforce (often 100–200).
    private const val MAX_AUTHORS_PER_SUB = 100
    private const val DEFAULT_COLD_START_WINDOW_SEC = 24L * 60L * 60L
    private const val OVERLAP_SEC = 5L * 60L
    private const val BATCH_FETCH_TIMEOUT_MS = 15_000L

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var listener: AggregatorListener? = null

    // Per-relay bookkeeping of active subscriptions so subIds stay stable across refreshes.
    // Quartz re-sends every desired REQ on reconnect via syncFilters(), so we don't track
    // these for resub purposes — only for preserving the subId set across refresh cycles
    // and for diagnosing which authors / since each chunk covers.
    private val activeRelaySubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>> =
        ConcurrentHashMap()

    // Same shape as [activeRelaySubs] but for the p-tag (inbox) subscription. Each entry's
    // `authors` set is the set of pubkeys we p-tag-filter on for that chunk, *not* the
    // event authors. Tracked separately so toggling `relayAggregatorIncludeTagged` only
    // tears down these entries.
    private val activeTaggedRelaySubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>> =
        ConcurrentHashMap()
    private val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Latest event createdAt observed per pubkey. Seeded from the local DB at the start of
    // every refresh and updated as events arrive on tracked subs. Used to compute a per-chunk
    // `since` so a newly-followed pubkey gets backfilled while caught-up authors don't refetch
    // their full window.
    private val lastEventByPubkey: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val eventsReceived = AtomicLong(0)

    @Volatile private var authorCount: Int = 0

    // Pubkey the currently-running aggregator was started for. Used by onConfigChanged
    // to detect identity changes and trigger a full restart.
    @Volatile private var currentPubkey: String? = null

    // Signals the next refresh to bypass the kind-3 DB cache (used after a pubkey change
    // so we don't reuse stale follows that happened to be sitting in the local DB).
    @Volatile private var forceFreshBootstrap: Boolean = false

    // True while subscriptions are torn down because the active network is metered and
    // [Settings.relayAggregatorWifiOnly] is enabled. The refresh loop checks this flag and
    // skips its body until [resumeFromMetered] resets it.
    @Volatile private var pausedForMetered: Boolean = false

    // The database the aggregator was started with — saved so the network callback can
    // trigger a refresh on resume without the caller threading it through.
    @Volatile private var database: AppDatabase? = null

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val refreshing = AtomicBoolean(false)

    private val _status = MutableStateFlow(AggregatorStatus())
    val status: StateFlow<AggregatorStatus> = _status.asStateFlow()

    @Synchronized
    fun start(database: AppDatabase) {
        if (scope != null) return
        if (!Settings.relayAggregatorEnabled) return
        val hasPubkey = Settings.aggregatorPubkey.isNotBlank()
        val hasExtraRelays = Settings.relayAggregatorExtraRelays.isNotEmpty()
        if (!hasPubkey && !hasExtraRelays) return

        val startLabel = if (hasPubkey) Settings.aggregatorPubkey else "no-pubkey mode (${Settings.relayAggregatorExtraRelays.size} relays)"
        Log.d(TAG, "Starting aggregator for $startLabel")
        currentPubkey = Settings.aggregatorPubkey
        this.database = database
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        val newListener = AggregatorListener()
        listener = newListener
        Citrine.instance.client.addConnectionListener(newListener)

        registerNetworkCallback()
        // Initial check: if we're already on a metered network and the user wants
        // wifi-only, start in the paused state instead of opening subs.
        evaluateNetworkState()

        if (!pausedForMetered) {
            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)
        }

        newScope.launch {
            while (isActive) {
                if (!pausedForMetered) {
                    try {
                        refreshAndSubscribe(database)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Refresh failed", e)
                    }
                }
                val minutes = Settings.relayAggregatorRefreshMinutes.coerceAtLeast(1)
                val interval = minutes * 60_000L
                Log.d(TAG, "Sleeping ${minutes}m until next refresh")
                delay(interval)
            }
        }
    }

    @Synchronized
    fun stop() {
        val s = scope ?: return
        Log.d(TAG, "Stopping aggregator")
        listener?.let { Citrine.instance.client.removeConnectionListener(it) }
        listener = null

        unregisterNetworkCallback()

        trackedSubIds.forEach {
            runCatching { Citrine.instance.client.unsubscribe(it) }
        }
        trackedSubIds.clear()
        activeRelaySubs.clear()
        activeTaggedRelaySubs.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
        lastEventByPubkey.clear()
        eventsReceived.set(0)
        authorCount = 0
        currentPubkey = null
        forceFreshBootstrap = false
        pausedForMetered = false
        database = null

        scope = null
        s.cancel()

        _status.value = AggregatorStatus(enabled = false, phase = AggregatorPhase.IDLE)
    }

    private fun connectivityManager(): ConnectivityManager? = Citrine.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun isOnMeteredNetwork(): Boolean {
        val cm = connectivityManager() ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        // Treat "no internet capability" as not-metered for our purposes — we'll have
        // bigger problems than bandwidth if there's no internet at all.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluateNetworkState()
            override fun onLost(network: Network) = evaluateNetworkState()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = evaluateNetworkState()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = connectivityManager() ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
            .onFailure { Log.e(TAG, "unregisterNetworkCallback failed", it) }
    }

    /**
     * Reconciles the current network state against the wifi-only setting and pauses or
     * resumes the aggregator accordingly. Cheap to call repeatedly; only acts when the
     * desired state diverges from the actual one.
     */
    @Synchronized
    private fun evaluateNetworkState() {
        if (scope == null) return
        val shouldPause = Settings.relayAggregatorWifiOnly && isOnMeteredNetwork()
        if (shouldPause && !pausedForMetered) {
            pauseForMetered()
        } else if (!shouldPause && pausedForMetered) {
            resumeFromMetered()
        }
    }

    private fun pauseForMetered() {
        Log.d(TAG, "Pausing aggregator: metered network detected and wifi-only enabled")
        pausedForMetered = true
        // Tear down active subscriptions so we stop receiving events. Keep
        // [lastEventByPubkey] and [currentPubkey] in place — when we resume, the next
        // refresh uses them to compute tight per-chunk `since` values.
        trackedSubIds.forEach { runCatching { Citrine.instance.client.unsubscribe(it) } }
        trackedSubIds.clear()
        activeRelaySubs.clear()
        activeTaggedRelaySubs.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
        publishStatus(phase = AggregatorPhase.PAUSED)
    }

    private fun resumeFromMetered() {
        Log.d(TAG, "Resuming aggregator: unmetered network available")
        pausedForMetered = false
        publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)
        val db = database ?: return
        scope?.launch {
            try {
                refreshAndSubscribe(db)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Resume refresh failed", e)
            }
        }
    }

    fun onConfigChanged(database: AppDatabase) {
        val pubkey = Settings.aggregatorPubkey
        val hasExtraRelays = Settings.relayAggregatorExtraRelays.isNotEmpty()
        val enabled = Settings.relayAggregatorEnabled && (pubkey.isNotBlank() || hasExtraRelays)
        val running = scope != null
        when {
            enabled && !running -> {
                Log.d(TAG, "Config change: aggregator enabled, starting")
                start(database)
            }
            !enabled && running -> {
                Log.d(TAG, "Config change: aggregator disabled or no pubkey+relays, stopping")
                stop()
            }
            enabled && running && pubkey != currentPubkey -> {
                // Identity changed: drop every existing subscription and rebuild from
                // scratch. Reset lastSync so the new user gets the full cold-start
                // window, and force a network fetch of their contact list — the
                // locally cached kind-3, if any, is whatever the relay happened to
                // receive incidentally and may not reflect the user's current follows.
                Log.d(TAG, "Aggregator pubkey changed ($currentPubkey -> $pubkey), restarting")
                stop()
                Settings.relayAggregatorLastSync = 0L
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
                forceFreshBootstrap = true
                start(database)
            }
            enabled && running -> {
                // The wifi-only toggle may have just changed; re-evaluate before
                // dispatching a refresh so a flip to "pause on metered" doesn't kick
                // off a doomed refresh that we'd immediately tear down.
                evaluateNetworkState()
                if (!pausedForMetered) {
                    scope?.launch {
                        Log.d(TAG, "Config change: triggering refresh for running aggregator")
                        try {
                            refreshAndSubscribe(database)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Config refresh failed", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshAndSubscribe(database: AppDatabase) {
        if (Citrine.isImportingEvents) {
            Log.d(TAG, "Import in progress; skipping refresh")
            return
        }
        if (pausedForMetered) {
            Log.d(TAG, "Paused for metered network; skipping refresh")
            return
        }
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already running; skipping")
            return
        }
        val refreshStartMs = System.currentTimeMillis()
        try {
            val aggPubkey = Settings.aggregatorPubkey
            val extraRelays = Settings.relayAggregatorExtraRelays
                .mapNotNull { raw -> normalizeRemote(raw) }
                .toSet()
            if (aggPubkey.isBlank() && extraRelays.isEmpty()) {
                Log.d(TAG, "No pubkey and no extra relays; nothing to refresh")
                return
            }
            val dao = database.eventDao()

            if (!Citrine.instance.client.isActive()) {
                Log.d(TAG, "Nostr client is not active; connecting")
                Citrine.instance.client.connect()
            }

            // When no pubkey is configured, the aggregator becomes a plain relay listener:
            // subscribe to every extra relay with the configured kinds and the sync window,
            // no author filter, no NIP-65 lookups, no tagged sub. This path uses the same
            // diff-vs-activeRelaySubs + cleanup logic as the pubkey path by representing
            // each relay as a single empty-author "chunk".
            val noPubkeyMode = aggPubkey.isBlank()
            val forceFresh = forceFreshBootstrap.also { if (it) forceFreshBootstrap = false }
            Log.d(
                TAG,
                if (noPubkeyMode) {
                    "Refresh starting: no-pubkey mode, ${extraRelays.size} relay(s)"
                } else {
                    "Refresh starting for $aggPubkey (forceFresh=$forceFresh, extraRelays=${extraRelays.size})"
                },
            )
            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)

            val cachedRelayLists = mutableMapOf<String, AdvertisedRelayListEvent>()
            val fetched: Map<String, AdvertisedRelayListEvent>
            val relayToAuthors = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()

            if (noPubkeyMode) {
                authorCount = 0
                fetched = emptyMap()
                extraRelays.forEach { relay ->
                    // Empty author set is a marker the subscribe loop recognizes to
                    // issue a filter without the `authors` field.
                    relayToAuthors[relay] = mutableSetOf()
                }
            } else {
                val contactListMs = System.currentTimeMillis()
                val contactList = loadOrBootstrapContactList(dao, aggPubkey, forceFresh)
                val follows = contactList?.verifiedFollowKeySet() ?: emptySet()
                val authors = (follows + aggPubkey).toSet()
                authorCount = authors.size
                Log.d(
                    TAG,
                    "Contact list resolved in ${System.currentTimeMillis() - contactListMs}ms: " +
                        "${follows.size} follows (+ self) for $aggPubkey (createdAt=${contactList?.createdAt ?: "none"})",
                )

                // Resolve NIP-65 for all authors in one batch instead of N sequential fetches.
                val uncached = mutableSetOf<String>()
                for (pk in authors) {
                    val cached = dao.getAdvertisedRelayList(pk)?.toEvent() as? AdvertisedRelayListEvent
                    if (cached != null) cachedRelayLists[pk] = cached else uncached.add(pk)
                }
                Log.d(TAG, "NIP-65 cache: ${cachedRelayLists.size} hit, ${uncached.size} miss")

                val fetchMs = System.currentTimeMillis()
                fetched = if (uncached.isNotEmpty()) {
                    Log.d(TAG, "Fetching ${uncached.size} NIP-65 records from ${INDEXER_RELAYS.size} indexers")
                    batchFetchRelayLists(uncached, INDEXER_RELAYS, BATCH_FETCH_TIMEOUT_MS)
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

                var fellBackCount = 0
                for (pk in authors) {
                    val relayListEvent = cachedRelayLists[pk] ?: fetched[pk]
                    val writeRelays = relayListEvent
                        ?.writeRelays()
                        ?.mapNotNull { raw -> normalizeRemote(raw) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            fellBackCount++
                            FALLBACK_OUTBOX_RELAYS
                        }
                    writeRelays.forEach { relay ->
                        relayToAuthors.getOrPut(relay) { mutableSetOf() }.add(pk)
                    }
                }
                Log.d(
                    TAG,
                    "Outbox map built: ${relayToAuthors.size} relays for ${authors.size} authors " +
                        "($fellBackCount author(s) had no NIP-65, fell back to ${FALLBACK_OUTBOX_RELAYS.size} relays)",
                )
            }

            publishStatus(phase = AggregatorPhase.REFRESHING)

            val kinds = Settings.relayAggregatorKinds.toList()
            val bootstrapSince = computeBootstrapSince()
            val allAuthors = relayToAuthors.values.flatten().toSet()
            seedLastEventTimestamps(dao, allAuthors, kinds)
            Log.d(
                TAG,
                "lastEvent map seeded: ${lastEventByPubkey.size} entries " +
                    "(${allAuthors.size} authors in scope, bootstrapSince=$bootstrapSince)",
            )
            val newActiveSubs = ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>()
            var reusedSubs = 0
            var freshSubs = 0
            var closedExtraChunkSubs = 0
            var coldChunks = 0
            var oldestChunkSince = Long.MAX_VALUE
            var newestChunkSince = Long.MIN_VALUE

            for ((relay, authorSet) in relayToAuthors) {
                // Empty author set → single unfiltered chunk (no-pubkey mode).
                val chunks: List<List<String>> = if (authorSet.isEmpty()) {
                    listOf(emptyList())
                } else {
                    // Sort by lastSeen DESC so chunks group authors with similar `since`
                    // together: caught-up authors share recent `since`, never-seen authors
                    // bucket into the tail chunk that pulls the cold-start window.
                    authorSet
                        .sortedByDescending { lastEventByPubkey[it] ?: 0L }
                        .chunked(MAX_AUTHORS_PER_SUB)
                }
                val existing = activeRelaySubs[relay] ?: emptyList()
                val entries = mutableListOf<ChunkSubscription>()
                chunks.forEachIndexed { idx, chunk ->
                    val reused = existing.getOrNull(idx)?.subId
                    val subId = reused ?: newSubId()
                    if (reused != null) reusedSubs++ else freshSubs++
                    val chunkSince = computeChunkSince(chunk, bootstrapSince)
                    if (chunk.isNotEmpty() && chunk.any { (lastEventByPubkey[it] ?: 0L) <= 0L }) {
                        coldChunks++
                    }
                    if (chunkSince < oldestChunkSince) oldestChunkSince = chunkSince
                    if (chunkSince > newestChunkSince) newestChunkSince = chunkSince
                    val filters = listOf(
                        Filter(
                            authors = if (chunk.isEmpty()) null else chunk,
                            kinds = kinds,
                            since = chunkSince,
                        ),
                    )
                    sendSubscribe(relay, subId, filters)
                    trackedSubIds.add(subId)
                    entries.add(
                        ChunkSubscription(
                            subId = subId,
                            authors = chunk.toSet(),
                            since = chunkSince,
                        ),
                    )
                }
                if (existing.size > chunks.size) {
                    for (i in chunks.size until existing.size) {
                        val oldId = existing[i].subId
                        runCatching { Citrine.instance.client.unsubscribe(oldId) }
                        trackedSubIds.remove(oldId)
                        closedExtraChunkSubs++
                    }
                }
                newActiveSubs[relay] = entries
            }

            var closedGoneRelaySubs = 0
            for ((relay, entries) in activeRelaySubs) {
                if (!newActiveSubs.containsKey(relay)) {
                    entries.forEach { entry ->
                        runCatching { Citrine.instance.client.unsubscribe(entry.subId) }
                        trackedSubIds.remove(entry.subId)
                        closedGoneRelaySubs++
                    }
                }
            }
            val sinceSpread = if (newestChunkSince >= oldestChunkSince) {
                "since spread=[$oldestChunkSince..$newestChunkSince]"
            } else {
                "since spread=n/a"
            }
            Log.d(
                TAG,
                "Main subs pushed: $freshSubs new, $reusedSubs reused, $coldChunks cold; " +
                    "closed $closedExtraChunkSubs shrunk chunks + $closedGoneRelaySubs dropped relays; " +
                    sinceSpread,
            )

            val newTaggedRelaySubs = ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>>()
            if (!noPubkeyMode && Settings.relayAggregatorIncludeTagged) {
                // Build inbox map: for each author (follows + self), record the relays
                // they listen on (NIP-65 read relays). Events tagging an author land on
                // their inbox, so to fetch "events that p-tag follow X" we query X's
                // inbox relays. Always include FALLBACK_OUTBOX_RELAYS so we still catch
                // taggers that publish to popular general-purpose relays even when an
                // author's NIP-65 inbox doesn't list them.
                val inboxToTaggees = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
                var taggeesFellBack = 0
                for (pk in allAuthors) {
                    val relayListEvent = cachedRelayLists[pk] ?: fetched[pk]
                    val readRelays = relayListEvent
                        ?.readRelays()
                        ?.mapNotNull { raw -> normalizeRemote(raw) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            taggeesFellBack++
                            emptyList()
                        }
                    (readRelays + FALLBACK_OUTBOX_RELAYS).toSet().forEach { relay ->
                        inboxToTaggees.getOrPut(relay) { mutableSetOf() }.add(pk)
                    }
                }

                var freshTaggedSubs = 0
                var reusedTaggedSubs = 0
                var closedExtraTaggedChunkSubs = 0
                for ((relay, taggeeSet) in inboxToTaggees) {
                    val sortedTaggees = taggeeSet
                        .sortedByDescending { lastEventByPubkey[it] ?: 0L }
                    val chunks = sortedTaggees.chunked(MAX_AUTHORS_PER_SUB)
                    val existing = activeTaggedRelaySubs[relay] ?: emptyList()
                    val entries = mutableListOf<ChunkSubscription>()
                    chunks.forEachIndexed { idx, chunk ->
                        val reused = existing.getOrNull(idx)?.subId
                        val subId = reused ?: newSubId()
                        if (reused != null) reusedTaggedSubs++ else freshTaggedSubs++
                        val filters = listOf(
                            Filter(
                                tags = mapOf("p" to chunk),
                                kinds = kinds,
                                since = bootstrapSince,
                            ),
                        )
                        sendSubscribe(relay, subId, filters)
                        trackedSubIds.add(subId)
                        entries.add(
                            ChunkSubscription(
                                subId = subId,
                                authors = chunk.toSet(),
                                since = bootstrapSince,
                            ),
                        )
                    }
                    if (existing.size > chunks.size) {
                        for (i in chunks.size until existing.size) {
                            val oldId = existing[i].subId
                            runCatching { Citrine.instance.client.unsubscribe(oldId) }
                            trackedSubIds.remove(oldId)
                            closedExtraTaggedChunkSubs++
                        }
                    }
                    newTaggedRelaySubs[relay] = entries
                }
                var closedGoneTaggedRelaySubs = 0
                for ((relay, entries) in activeTaggedRelaySubs) {
                    if (!newTaggedRelaySubs.containsKey(relay)) {
                        entries.forEach { entry ->
                            runCatching { Citrine.instance.client.unsubscribe(entry.subId) }
                            trackedSubIds.remove(entry.subId)
                            closedGoneTaggedRelaySubs++
                        }
                    }
                }
                Log.d(
                    TAG,
                    "Tagged subs pushed: $freshTaggedSubs new, $reusedTaggedSubs reused " +
                        "across ${inboxToTaggees.size} inbox relays " +
                        "($taggeesFellBack author(s) had no NIP-65 inbox); " +
                        "closed $closedExtraTaggedChunkSubs shrunk + $closedGoneTaggedRelaySubs dropped",
                )
            } else if (activeTaggedRelaySubs.isNotEmpty()) {
                Log.d(TAG, "Tagged sub disabled, unsubscribing ${activeTaggedRelaySubs.size} relay entries")
                activeTaggedRelaySubs.values.forEach { entries ->
                    entries.forEach { entry ->
                        runCatching { Citrine.instance.client.unsubscribe(entry.subId) }
                        trackedSubIds.remove(entry.subId)
                    }
                }
            }

            activeRelaySubs.clear()
            activeRelaySubs.putAll(newActiveSubs)
            activeTaggedRelaySubs.clear()
            activeTaggedRelaySubs.putAll(newTaggedRelaySubs)

            val newSubscribedRelays = newActiveSubs.keys + newTaggedRelaySubs.keys
            subscribedRelays.clear()
            subscribedRelays.addAll(newSubscribedRelays)
            // Drop liveness entries for relays we no longer track
            connectedRelays.retainAll(newSubscribedRelays)

            Settings.relayAggregatorLastSync = TimeUtils.now()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
            Log.d(
                TAG,
                "Refresh complete in ${System.currentTimeMillis() - refreshStartMs}ms: " +
                    "$authorCount authors, ${newSubscribedRelays.size} relays, " +
                    "${trackedSubIds.size} tracked subIds, " +
                    "noPubkey=$noPubkeyMode, tagged=${Settings.relayAggregatorIncludeTagged}",
            )
            publishStatus(phase = AggregatorPhase.LISTENING)
        } finally {
            refreshing.set(false)
        }
    }

    private fun sendSubscribe(relay: NormalizedRelayUrl, subId: String, filters: List<Filter>) {
        runCatching {
            Citrine.instance.client.subscribe(subId, mapOf(relay to filters))
        }.onFailure { Log.e(TAG, "subscribe to ${relay.url} failed", it) }
    }

    private fun publishStatus(phase: AggregatorPhase) {
        _status.value = AggregatorStatus(
            enabled = scope != null,
            phase = phase,
            authors = authorCount,
            relaysConfigured = subscribedRelays.size,
            relaysConnected = connectedRelays.size,
            eventsReceived = eventsReceived.get(),
            lastRefreshEpoch = Settings.relayAggregatorLastSync,
        )
    }

    private fun refreshStatusCounters() {
        val current = _status.value
        _status.value = current.copy(
            relaysConfigured = subscribedRelays.size,
            relaysConnected = connectedRelays.size,
            eventsReceived = eventsReceived.get(),
        )
    }

    /**
     * Bootstrap `since` used when there is no per-author history to draw from
     * (no-pubkey mode, the tagged sub, and as the cold-start fallback for chunks
     * whose authors are all unknown to us).
     */
    private fun computeBootstrapSince(): Long {
        val now = TimeUtils.now()
        val last = Settings.relayAggregatorLastSync
        return if (last <= 0L) {
            now - DEFAULT_COLD_START_WINDOW_SEC
        } else {
            maxOf(last - OVERLAP_SEC, now - DEFAULT_COLD_START_WINDOW_SEC)
        }
    }

    /**
     * Per-chunk `since`: the oldest lastSeen of any author in [chunk] minus an overlap window.
     * If any author has no recorded lastSeen, we fall back to the cold-start window so a
     * brand-new follow gets backfilled instead of inheriting the rest of the chunk's recent
     * cursor.
     */
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
        if (authors.isEmpty() || kinds.isEmpty()) return
        // SQLite caps bound parameters at ~999; chunk to stay well under that with two
        // IN-lists in the same query.
        val chunkSize = 400
        val authorChunks = authors.toList().chunked(chunkSize)
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
        rows.forEach { row ->
            // Keep the larger of (DB seed, in-memory live update). A live update that arrived
            // mid-refresh shouldn't be rolled back by a slightly older DB read.
            lastEventByPubkey.merge(row.pubkey, row.createdAt) { a, b -> maxOf(a, b) }
        }
    }

    private fun normalizeRemote(raw: String): NormalizedRelayUrl? {
        val n = RelayUrlNormalizer.normalizeOrNull(raw) ?: return null
        if (Citrine.instance.isPrivateIp(n.url)) return null
        return NormalizedRelayUrl(url = n.url)
    }

    private suspend fun loadOrBootstrapContactList(
        dao: EventDao,
        pubkey: String,
        forceRefresh: Boolean = false,
    ): ContactListEvent? {
        val cached = dao.getContactList(pubkey)?.toEvent() as? ContactListEvent
        if (!forceRefresh && cached != null) return cached
        val filters = listOf(
            Filter(
                kinds = listOf(ContactListEvent.KIND),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()
        val fetched = try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                Citrine.instance.client.fetchFirst(subId, INDEXER_RELAYS.associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap contact list failed for $pubkey", e)
            null
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
        }
        fetched?.let { CustomWebSocketService.server?.innerProcessEvent(it, null) }
        // Prefer whichever copy is newest — a cache hit may still beat a slow/partial fetch.
        val fetchedContactList = fetched as? ContactListEvent
        return when {
            fetchedContactList == null -> cached
            cached == null -> fetchedContactList
            fetchedContactList.createdAt >= cached.createdAt -> fetchedContactList
            else -> cached
        }
    }

    /**
     * Issues one subscription to all [relays] asking for kind-10002 events authored by any
     * pubkey in [pubkeys] and collects the latest per author. Returns as soon as every relay
     * sends EOSE or after [timeoutMs] milliseconds, whichever comes first.
     */
    private suspend fun batchFetchRelayLists(
        pubkeys: Set<String>,
        relays: List<NormalizedRelayUrl>,
        timeoutMs: Long,
    ): Map<String, AdvertisedRelayListEvent> {
        if (pubkeys.isEmpty() || relays.isEmpty()) return emptyMap()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()

        val results = ConcurrentHashMap<String, AdvertisedRelayListEvent>()
        val subId = newSubId()
        val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
        val expected = relays.toSet()
        val done = CompletableDeferred<Unit>()

        val collector = object : RelayConnectionListener {
            override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
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

        Citrine.instance.client.addConnectionListener(collector)
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
                Citrine.instance.client.subscribe(subId, relays.associateWith { filters })
            }.onFailure { Log.e(TAG, "batch relay-list subscribe failed", it) }
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
            Citrine.instance.client.removeConnectionListener(collector)
        }

        // Persist what we learned so future refreshes are a cache hit.
        results.values.forEach {
            runCatching { CustomWebSocketService.server?.innerProcessEvent(it, null) }
        }
        return results
    }

    private class AggregatorListener : RelayConnectionListener {
        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            if (subscribedRelays.contains(relay.url)) {
                if (connectedRelays.add(relay.url)) {
                    Log.d(TAG, "Relay live (${connectedRelays.size}/${subscribedRelays.size}): ${relay.url.url}")
                    refreshStatusCounters()
                }
            }
            if (msg is EventMessage && trackedSubIds.contains(msg.subId)) {
                val ev = msg.event
                // Track lastSeen by author so the next refresh can compute a tighter
                // per-chunk `since`. Update for every received event — events from
                // tagged subs (random pubkeys) cost a bit of memory but are harmless;
                // events from main subs are exactly what we want to record.
                lastEventByPubkey.merge(ev.pubKey, ev.createdAt) { a, b -> maxOf(a, b) }
                val n = eventsReceived.incrementAndGet()
                // Periodic progress heartbeat so we can see the stream without per-event spam.
                if (n == 1L || n % 100L == 0L) {
                    Log.d(
                        TAG,
                        "Events received: $n (${connectedRelays.size}/${subscribedRelays.size} relays connected)",
                    )
                }
                refreshStatusCounters()
                val s = scope ?: return
                s.launch {
                    try {
                        CustomWebSocketService.server?.innerProcessEvent(ev, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "innerProcessEvent failed", e)
                    }
                }
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            // Quartz's NostrClient.onConnected() calls syncFilters() on reconnect, which
            // re-issues every REQ stored in desiredSubs for that relay. We don't re-send
            // anything from here — doing so on every disconnect / onCannotConnect turns
            // unreachable relays into a resub storm.
            if (connectedRelays.remove(relay.url)) {
                Log.d(TAG, "Disconnected from ${relay.url.url} (now ${connectedRelays.size}/${subscribedRelays.size})")
                refreshStatusCounters()
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            if (subscribedRelays.contains(relay.url)) {
                Log.d(TAG, "Cannot connect to ${relay.url.url}: $errorMessage")
            }
            if (connectedRelays.remove(relay.url)) {
                refreshStatusCounters()
            }
        }
    }
}
