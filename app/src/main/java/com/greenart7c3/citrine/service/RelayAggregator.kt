package com.greenart7c3.citrine.service

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
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

    private const val MAX_AUTHORS_PER_SUB = 500
    private const val DEFAULT_COLD_START_WINDOW_SEC = 24L * 60L * 60L
    private const val OVERLAP_SEC = 5L * 60L
    private const val BATCH_FETCH_TIMEOUT_MS = 15_000L
    private const val RESUB_DELAY_MS = 1_500L

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var listener: AggregatorListener? = null

    // Per-relay bookkeeping of active subscriptions so we can re-send them after a reconnect.
    // relay -> list of (subId, filters)
    private val activeRelaySubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<Pair<String, List<Filter>>>> =
        ConcurrentHashMap()
    private val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val eventsReceived = AtomicLong(0)
    private val pendingResub: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()

    @Volatile private var authorCount: Int = 0

    @Volatile private var taggedSubId: String? = null
    private val refreshing = AtomicBoolean(false)

    private val _status = MutableStateFlow(AggregatorStatus())
    val status: StateFlow<AggregatorStatus> = _status.asStateFlow()

    @Synchronized
    fun start(database: AppDatabase) {
        if (scope != null) return
        if (!Settings.relayAggregatorEnabled) return
        if (Settings.aggregatorPubkey.isBlank()) return

        Log.d(TAG, "Starting aggregator for ${Settings.aggregatorPubkey}")
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        val newListener = AggregatorListener()
        listener = newListener
        Citrine.instance.client.addConnectionListener(newListener)

        publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)

        newScope.launch {
            while (isActive) {
                try {
                    refreshAndSubscribe(database)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed", e)
                }
                val interval = Settings.relayAggregatorRefreshMinutes.coerceAtLeast(1) * 60_000L
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

        trackedSubIds.forEach {
            runCatching { Citrine.instance.client.unsubscribe(it) }
        }
        trackedSubIds.clear()
        activeRelaySubs.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
        pendingResub.clear()
        authorCount = 0
        taggedSubId = null

        scope = null
        s.cancel()

        _status.value = AggregatorStatus(enabled = false, phase = AggregatorPhase.IDLE)
    }

    fun onConfigChanged(database: AppDatabase) {
        val enabled = Settings.relayAggregatorEnabled && Settings.aggregatorPubkey.isNotBlank()
        val running = scope != null
        when {
            enabled && !running -> start(database)
            !enabled && running -> stop()
            enabled && running -> scope?.launch {
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

    private suspend fun refreshAndSubscribe(database: AppDatabase) {
        if (Citrine.isImportingEvents) {
            Log.d(TAG, "Import in progress; skipping refresh")
            return
        }
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already running; skipping")
            return
        }
        try {
            val aggPubkey = Settings.aggregatorPubkey
            if (aggPubkey.isBlank()) return
            val dao = database.eventDao()

            if (!Citrine.instance.client.isActive()) {
                Citrine.instance.client.connect()
            }

            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)

            val contactList = loadOrBootstrapContactList(dao, aggPubkey)
            val follows = contactList?.verifiedFollowKeySet() ?: emptySet()
            val authors = (follows + aggPubkey).toSet()
            authorCount = authors.size
            Log.d(TAG, "Loaded ${follows.size} follows (+ self) for $aggPubkey")

            // Resolve NIP-65 for all authors in one batch instead of N sequential fetches.
            val cachedRelayLists = mutableMapOf<String, AdvertisedRelayListEvent>()
            val uncached = mutableSetOf<String>()
            for (pk in authors) {
                val cached = dao.getAdvertisedRelayList(pk)?.toEvent() as? AdvertisedRelayListEvent
                if (cached != null) cachedRelayLists[pk] = cached else uncached.add(pk)
            }
            Log.d(TAG, "NIP-65 cache: ${cachedRelayLists.size} hit, ${uncached.size} miss")

            val fetched = if (uncached.isNotEmpty()) {
                batchFetchRelayLists(uncached, INDEXER_RELAYS, BATCH_FETCH_TIMEOUT_MS)
            } else {
                emptyMap()
            }
            Log.d(TAG, "NIP-65 batch fetched ${fetched.size} lists from indexers")

            val relayToAuthors = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
            for (pk in authors) {
                val relayListEvent = cachedRelayLists[pk] ?: fetched[pk]
                val writeRelays = relayListEvent
                    ?.writeRelays()
                    ?.mapNotNull { raw -> normalizeRemote(raw) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: FALLBACK_OUTBOX_RELAYS
                writeRelays.forEach { relay ->
                    relayToAuthors.getOrPut(relay) { mutableSetOf() }.add(pk)
                }
            }

            publishStatus(phase = AggregatorPhase.REFRESHING)

            val since = computeSince()
            val kinds = Settings.relayAggregatorKinds.toList()
            val newActiveSubs = ConcurrentHashMap<NormalizedRelayUrl, MutableList<Pair<String, List<Filter>>>>()

            for ((relay, authorSet) in relayToAuthors) {
                val chunks = authorSet.toList().chunked(MAX_AUTHORS_PER_SUB)
                val existing = activeRelaySubs[relay] ?: emptyList()
                val entries = mutableListOf<Pair<String, List<Filter>>>()
                chunks.forEachIndexed { idx, chunk ->
                    val subId = existing.getOrNull(idx)?.first ?: newSubId()
                    val filters = listOf(
                        Filter(
                            authors = chunk,
                            kinds = kinds,
                            since = since,
                        ),
                    )
                    sendSubscribe(relay, subId, filters)
                    trackedSubIds.add(subId)
                    entries.add(subId to filters)
                }
                if (existing.size > chunks.size) {
                    for (i in chunks.size until existing.size) {
                        val (oldId, _) = existing[i]
                        runCatching { Citrine.instance.client.unsubscribe(oldId) }
                        trackedSubIds.remove(oldId)
                    }
                }
                newActiveSubs[relay] = entries
            }

            for ((relay, entries) in activeRelaySubs) {
                if (!newActiveSubs.containsKey(relay)) {
                    entries.forEach { (oldId, _) ->
                        runCatching { Citrine.instance.client.unsubscribe(oldId) }
                        trackedSubIds.remove(oldId)
                    }
                }
            }

            val taggedSubRelays: Set<NormalizedRelayUrl>
            if (Settings.relayAggregatorIncludeTagged) {
                val aggRelayList = cachedRelayLists[aggPubkey] ?: fetched[aggPubkey]
                val readRelays = aggRelayList?.readRelays()
                    ?.mapNotNull { raw -> normalizeRemote(raw) }
                    ?: emptyList()
                taggedSubRelays = (readRelays + FALLBACK_OUTBOX_RELAYS).toSet()
                val id = taggedSubId ?: newSubId().also { taggedSubId = it }
                trackedSubIds.add(id)
                val filters = listOf(
                    Filter(
                        tags = mapOf("p" to listOf(aggPubkey)),
                        since = since,
                    ),
                )
                taggedSubRelays.forEach { relay ->
                    sendSubscribe(relay, id, filters)
                    newActiveSubs.getOrPut(relay) { mutableListOf() }.add(id to filters)
                }
            } else {
                taggedSubRelays = emptySet()
                taggedSubId?.let {
                    runCatching { Citrine.instance.client.unsubscribe(it) }
                    trackedSubIds.remove(it)
                }
                taggedSubId = null
            }

            activeRelaySubs.clear()
            activeRelaySubs.putAll(newActiveSubs)

            val newSubscribedRelays = newActiveSubs.keys
            subscribedRelays.clear()
            subscribedRelays.addAll(newSubscribedRelays)
            // Drop liveness entries for relays we no longer track
            connectedRelays.retainAll(newSubscribedRelays)

            Settings.relayAggregatorLastSync = TimeUtils.now()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
            Log.d(
                TAG,
                "Refreshed: ${authors.size} authors across ${newSubscribedRelays.size} relays, tagged=${Settings.relayAggregatorIncludeTagged}",
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

    private fun resendSubsForRelay(relay: NormalizedRelayUrl) {
        val entries = activeRelaySubs[relay] ?: return
        if (entries.isEmpty()) return
        Log.d(TAG, "Re-sending ${entries.size} subscription(s) to ${relay.url}")
        entries.forEach { (subId, filters) ->
            sendSubscribe(relay, subId, filters)
        }
    }

    private fun scheduleResub(relay: NormalizedRelayUrl) {
        val s = scope ?: return
        if (!pendingResub.add(relay)) return
        s.launch {
            delay(RESUB_DELAY_MS)
            pendingResub.remove(relay)
            if (!Citrine.instance.client.isActive()) {
                runCatching { Citrine.instance.client.connect() }
            }
            resendSubsForRelay(relay)
        }
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

    private fun computeSince(): Long {
        val now = TimeUtils.now()
        val last = Settings.relayAggregatorLastSync
        return if (last <= 0L) {
            now - DEFAULT_COLD_START_WINDOW_SEC
        } else {
            maxOf(last - OVERLAP_SEC, now - DEFAULT_COLD_START_WINDOW_SEC)
        }
    }

    private fun normalizeRemote(raw: String): NormalizedRelayUrl? {
        val n = RelayUrlNormalizer.normalizeOrNull(raw) ?: return null
        if (Citrine.instance.isPrivateIp(n.url)) return null
        return NormalizedRelayUrl(url = n.url)
    }

    private suspend fun loadOrBootstrapContactList(dao: EventDao, pubkey: String): ContactListEvent? {
        dao.getContactList(pubkey)?.toEvent()?.let { return it as? ContactListEvent }
        val filters = listOf(
            Filter(
                kinds = listOf(ContactListEvent.KIND),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()
        val event = try {
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
        event?.let { CustomWebSocketService.server?.innerProcessEvent(it, null) }
        return event as? ContactListEvent
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
            // Chunk the authors list to stay well under common relay filter size caps.
            val chunks = pubkeys.toList().chunked(MAX_AUTHORS_PER_SUB)
            chunks.forEach { chunk ->
                val filters = listOf(
                    Filter(
                        kinds = listOf(AdvertisedRelayListEvent.KIND),
                        authors = chunk,
                    ),
                )
                runCatching {
                    Citrine.instance.client.subscribe(subId, relays.associateWith { filters })
                }.onFailure { Log.e(TAG, "batch relay-list subscribe failed", it) }
            }
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
                    refreshStatusCounters()
                }
            }
            if (msg is EventMessage && trackedSubIds.contains(msg.subId)) {
                eventsReceived.incrementAndGet()
                refreshStatusCounters()
                val s = scope ?: return
                s.launch {
                    try {
                        CustomWebSocketService.server?.innerProcessEvent(msg.event, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "innerProcessEvent failed", e)
                    }
                }
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            Log.d(TAG, "Disconnected from ${relay.url}")
            if (connectedRelays.remove(relay.url)) {
                refreshStatusCounters()
            }
            if (subscribedRelays.contains(relay.url)) {
                // Quartz reconnects on its own but doesn't re-send our REQs; push them again.
                scheduleResub(relay.url)
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            Log.d(TAG, "Cannot connect to ${relay.url}: $errorMessage")
            if (connectedRelays.remove(relay.url)) {
                refreshStatusCounters()
            }
            if (subscribedRelays.contains(relay.url)) {
                scheduleResub(relay.url)
            }
        }
    }
}
