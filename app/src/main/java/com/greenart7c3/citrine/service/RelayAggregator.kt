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
    private const val RECONNECT_REFRESH_DEBOUNCE_MS = 30_000L
    private const val DEFAULT_COLD_START_WINDOW_SEC = 24L * 60L * 60L
    private const val OVERLAP_SEC = 5L * 60L

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var listener: AggregatorListener? = null

    private val relaySubs = mutableMapOf<NormalizedRelayUrl, MutableList<String>>()
    private val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val eventsReceived = AtomicLong(0)

    @Volatile private var authorCount: Int = 0

    @Volatile private var taggedSubId: String? = null
    private val refreshing = AtomicBoolean(false)

    @Volatile private var lastReconnectRefresh = 0L

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
        relaySubs.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
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

            val relayToAuthors = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
            for (pk in authors) {
                val writeRelays = loadOrBootstrapAdvertisedRelayList(dao, pk)
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
            val newRelaySubs = mutableMapOf<NormalizedRelayUrl, MutableList<String>>()

            for ((relay, authorSet) in relayToAuthors) {
                val chunks = authorSet.toList().chunked(MAX_AUTHORS_PER_SUB)
                val existing = relaySubs[relay] ?: emptyList()
                val subIds = mutableListOf<String>()
                chunks.forEachIndexed { idx, chunk ->
                    val subId = existing.getOrNull(idx) ?: newSubId()
                    val filter = Filter(
                        authors = chunk,
                        kinds = kinds,
                        since = since,
                    )
                    runCatching {
                        Citrine.instance.client.subscribe(subId, mapOf(relay to listOf(filter)))
                    }.onFailure { Log.e(TAG, "subscribe to ${relay.url} failed", it) }
                    subIds.add(subId)
                    trackedSubIds.add(subId)
                }
                if (existing.size > chunks.size) {
                    for (i in chunks.size until existing.size) {
                        val old = existing[i]
                        runCatching { Citrine.instance.client.unsubscribe(old) }
                        trackedSubIds.remove(old)
                    }
                }
                newRelaySubs[relay] = subIds
            }

            for ((relay, ids) in relaySubs) {
                if (!newRelaySubs.containsKey(relay)) {
                    ids.forEach {
                        runCatching { Citrine.instance.client.unsubscribe(it) }
                        trackedSubIds.remove(it)
                    }
                }
            }
            relaySubs.clear()
            relaySubs.putAll(newRelaySubs)

            val taggedSubRelays: Set<NormalizedRelayUrl>
            if (Settings.relayAggregatorIncludeTagged) {
                val readRelays = loadOrBootstrapAdvertisedRelayList(dao, aggPubkey)
                    ?.readRelays()
                    ?.mapNotNull { raw -> normalizeRemote(raw) }
                    ?: emptyList()
                taggedSubRelays = (readRelays + FALLBACK_OUTBOX_RELAYS).toSet()
                val id = taggedSubId ?: newSubId().also { taggedSubId = it }
                trackedSubIds.add(id)
                val filter = Filter(
                    tags = mapOf("p" to listOf(aggPubkey)),
                    since = since,
                )
                runCatching {
                    Citrine.instance.client.subscribe(id, taggedSubRelays.associateWith { listOf(filter) })
                }.onFailure { Log.e(TAG, "tagged subscribe failed", it) }
            } else {
                taggedSubRelays = emptySet()
                taggedSubId?.let {
                    runCatching { Citrine.instance.client.unsubscribe(it) }
                    trackedSubIds.remove(it)
                }
                taggedSubId = null
            }

            val newSubscribedRelays = relayToAuthors.keys + taggedSubRelays
            subscribedRelays.clear()
            subscribedRelays.addAll(newSubscribedRelays)
            // Drop liveness entries for relays we no longer track
            connectedRelays.retainAll(newSubscribedRelays)

            Settings.relayAggregatorLastSync = TimeUtils.now()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
            Log.d(
                TAG,
                "Refreshed: ${authors.size} authors across ${relayToAuthors.size} relays, tagged=${Settings.relayAggregatorIncludeTagged}",
            )
            publishStatus(phase = AggregatorPhase.LISTENING)
        } finally {
            refreshing.set(false)
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
            Citrine.instance.client.fetchFirst(subId, INDEXER_RELAYS.associateWith { filters })
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

    private suspend fun loadOrBootstrapAdvertisedRelayList(dao: EventDao, pubkey: String): AdvertisedRelayListEvent? {
        dao.getAdvertisedRelayList(pubkey)?.toEvent()?.let { return it as? AdvertisedRelayListEvent }
        val filters = listOf(
            Filter(
                kinds = listOf(AdvertisedRelayListEvent.KIND),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()
        val event = try {
            Citrine.instance.client.fetchFirst(subId, INDEXER_RELAYS.associateWith { filters })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap relay list failed for $pubkey", e)
            null
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
        }
        event?.let { CustomWebSocketService.server?.innerProcessEvent(it, null) }
        return event as? AdvertisedRelayListEvent
    }

    private fun kickReconnectRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectRefresh < RECONNECT_REFRESH_DEBOUNCE_MS) return
        lastReconnectRefresh = now
        val s = scope ?: return
        s.launch {
            delay(2_000)
            try {
                refreshAndSubscribe(AppDatabase.getDatabase(Citrine.instance))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "reconnect refresh failed", e)
            }
        }
    }

    private class AggregatorListener : RelayConnectionListener {
        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            if (subscribedRelays.contains(relay.url)) {
                // Any incoming traffic from a tracked relay means we're alive on it.
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
            kickReconnectRefresh()
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            Log.d(TAG, "Cannot connect to ${relay.url}: $errorMessage")
            if (connectedRelays.remove(relay.url)) {
                refreshStatusCounters()
            }
        }
    }
}
