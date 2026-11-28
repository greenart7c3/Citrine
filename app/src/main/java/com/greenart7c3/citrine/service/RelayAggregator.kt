package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.LruCache
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.EventMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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

private const val TAG = "RelayAggregator"

internal val FALLBACK_OUTBOX_RELAYS = listOf(
    RelayUrlNormalizer.normalize("wss://nos.lol/"),
    RelayUrlNormalizer.normalize("wss://relay.primal.net/"),
)

// Keep well under the per-filter author cap that stricter relays enforce (often 100–200).
@Suppress("NoComments")
internal const val MAX_AUTHORS_PER_SUB = 100

internal const val BATCH_FETCH_TIMEOUT_MS = 15_000L

private const val MIN_REFRESH_INTERVAL_MS = 30_000L

private const val MAX_FAILURE_BACKOFF_MULTIPLIER = 8

private const val EVENT_CHANNEL_CAPACITY = 256

private const val NETWORK_DEBOUNCE_MS = 2_000L

private const val NETWORK_SETTLE_MS = 1_000L

private const val STATUS_EMIT_INTERVAL_MS = 500L

private const val MAX_BACKFILL_SEEN_PUBKEYS = 10_000

private const val PROGRESS_LOG_EVERY = 100L

object RelayAggregator {
    private lateinit var context: Context
    private lateinit var relayClient: RelayClient
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private lateinit var fetcher: AggregatorFetcher
    private lateinit var subscriptions: AggregatorSubscriptions
    private lateinit var muteList: AggregatorMuteList

    /**
     * Hands the aggregator the application context (read only once it is attached), the
     * outbound relay client and the exception handler of its scope; called once from the
     * Citrine constructor.
     */
    fun install(context: Context, relayClient: RelayClient, exceptionHandler: CoroutineExceptionHandler) {
        this.context = context
        this.relayClient = relayClient
        this.exceptionHandler = exceptionHandler
        fetcher = AggregatorFetcher(relayClient)
        subscriptions = AggregatorSubscriptions(relayClient, fetcher)
        muteList = AggregatorMuteList(fetcher, { scope }, { relayAuth.relayAuthSigner })
    }

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var listener: AggregatorListener? = null

    private val backfilledPubkeys = LruCache<String, Boolean>(MAX_BACKFILL_SEEN_PUBKEYS)

    private val eventsReceived = AtomicLong(0)

    @Volatile private var followedAuthors: Set<String> = emptySet()

    @Volatile private var authorCount: Int = 0

    @Volatile private var currentPubkey: String? = null

    @Volatile private var pausedForNetwork: Boolean = false

    @Volatile private var database: AppDatabase? = null

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var networkChangeDetector = NetworkChangeDetector()

    @Volatile private var pendingRelayReset: Job? = null

    private val relayAuth = AggregatorRelayAuth()

    /**
     * Called by [MainActivity] in onCreate to provide an ActivityResultLauncher-backed
     * way to launch the signer intent. The aggregator's foreground signer path goes
     * through this when invoked while the activity is alive.
     */
    fun registerActivityLauncher(launcher: (Intent) -> Unit) {
        relayAuth.activityLauncher = launcher
    }

    fun unregisterActivityLauncher() {
        relayAuth.activityLauncher = null
    }

    @Volatile private var networkEvalJob: Job? = null

    @Volatile private var eventChannel: Channel<Event>? = null

    @Volatile private var backfillChannel: Channel<String>? = null

    @Volatile private var lastRefreshFinishedAt: Long = 0L

    @Volatile private var consecutiveFailures: Int = 0

    private val droppedEvents = AtomicLong(0)

    private val statusDirty = AtomicBoolean(false)

    @Volatile private var statusTrigger: Channel<Unit>? = null

    private val refreshing = AtomicBoolean(false)

    private val _status = MutableStateFlow(AggregatorStatus())

    /**
     * The aggregator's phase and counters. Phase changes are published immediately;
     * counter-only changes (relay and event counts) are coalesced to at most one update per
     * [STATUS_EMIT_INTERVAL_MS], so a collector does not recompose for every received event.
     */
    val status: StateFlow<AggregatorStatus> = _status.asStateFlow()

    /**
     * Starts the aggregator for the configured pubkey, or for the extra relays alone when no
     * pubkey is set. Does nothing when it is already running, disabled, or has neither. When
     * the current network matches a pause setting (wifi-only on a metered network, or pause
     * on a limited one), it starts paused instead of opening subscriptions.
     */
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
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
        scope = newScope

        val newEventChannel = Channel<Event>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        val newBackfillChannel = Channel<String>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        val newStatusTrigger = Channel<Unit>(Channel.CONFLATED)
        eventChannel = newEventChannel
        backfillChannel = newBackfillChannel
        statusTrigger = newStatusTrigger

        launchEventConsumer(newScope, newEventChannel, database)

        launchBackfillConsumer(newScope, newBackfillChannel, database)

        launchStatusFlusher(newScope, newStatusTrigger)

        val newListener = AggregatorListener()
        listener = newListener
        relayClient.client.addConnectionListener(newListener)

        relayAuth.installAuthenticatorIfNeeded(newScope, context, relayClient)

        registerNetworkCallback()
        evaluateNetworkState()

        if (!pausedForNetwork) {
            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)
        }

        if (Settings.aggregatorPubkey.isNotBlank()) {
            launchWarmMuteListLoad(newScope, database)
        }

        launchRefreshLoop(newScope, database)
    }

    private fun launchEventConsumer(scope: CoroutineScope, channel: Channel<Event>, database: AppDatabase) {
        scope.launch {
            for (ev in channel) {
                try {
                    if (muteList.shouldDropForMute(ev)) {
                        droppedEvents.incrementAndGet()
                        continue
                    }
                    CustomWebSocketService.server?.innerProcessEvent(ev, null, fromAggregator = true)
                    if (isNewerOwnerMuteList(ev)) {
                        muteList.scheduleMuteListRefresh(database)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "innerProcessEvent failed", e)
                }
            }
        }
    }

    private fun isNewerOwnerMuteList(ev: Event): Boolean = ev.kind == 10000 && ev.pubKey == Settings.aggregatorPubkey && ev.createdAt > muteList.muteListSeenAt

    private fun launchBackfillConsumer(scope: CoroutineScope, channel: Channel<String>, database: AppDatabase) {
        scope.launch {
            for (pubkey in channel) {
                try {
                    fetcher.backfillUserIfMissing(database.eventDao(), pubkey) { followedAuthors }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "backfill for $pubkey failed", e)
                }
            }
        }
    }

    private fun launchStatusFlusher(scope: CoroutineScope, trigger: Channel<Unit>) {
        scope.launch {
            @Suppress("UNUSED_VARIABLE")
            for (signal in trigger) {
                delay(STATUS_EMIT_INTERVAL_MS)
                flushPendingStatus()
            }
        }
    }

    private fun launchWarmMuteListLoad(scope: CoroutineScope, database: AppDatabase) {
        scope.launch {
            try {
                muteList.loadCachedMuteListFromDb(database.eventDao(), Settings.aggregatorPubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Warm mute-list load failed", e)
            }
        }
    }

    private fun launchRefreshLoop(scope: CoroutineScope, database: AppDatabase) {
        scope.launch {
            while (isActive) {
                if (!pausedForNetwork) {
                    try {
                        val refreshRan = refreshAndSubscribe(database)
                        if (!refreshRan) {
                            delay(MIN_REFRESH_INTERVAL_MS)
                            continue
                        }
                        consecutiveFailures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveFailures++
                        Log.e(TAG, "Refresh failed (consecutiveFailures=$consecutiveFailures)", e)
                    }
                }
                val minutes = Settings.relayAggregatorRefreshMinutes.coerceAtLeast(1)
                val interval = minutes * 60_000L
                val multiplier = if (consecutiveFailures <= 0) {
                    1
                } else {
                    val shift = consecutiveFailures.coerceAtMost(30)
                    (1 shl shift).coerceAtMost(MAX_FAILURE_BACKOFF_MULTIPLIER)
                }
                val sleepMs = interval * multiplier
                if (multiplier > 1) {
                    Log.d(TAG, "Sleeping ${minutes}m × $multiplier until next refresh (backoff)")
                } else {
                    Log.d(TAG, "Sleeping ${minutes}m until next refresh")
                }
                delay(sleepMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        val s = scope ?: return
        Log.d(TAG, "Stopping aggregator")
        listener?.let { relayClient.client.removeConnectionListener(it) }
        listener = null

        relayAuth.destroy()

        unregisterNetworkCallback()

        subscriptions.trackedSubIds.forEach {
            runCatching { relayClient.client.unsubscribe(it) }
        }
        subscriptions.trackedSubIds.clear()
        subscriptions.taggedSubIds.clear()
        subscriptions.activeRelaySubs.clear()
        subscriptions.subscribedRelays.clear()
        subscriptions.connectedRelays.clear()
        subscriptions.lastEventByPubkey.clear()
        muteList.reset()
        followedAuthors = emptySet()
        backfilledPubkeys.evictAll()
        eventsReceived.set(0)
        droppedEvents.set(0)
        statusDirty.set(false)
        authorCount = 0
        subscriptions.taggedSubIds.clear()
        currentPubkey = null
        pausedForNetwork = false
        database = null
        lastRefreshFinishedAt = 0L
        consecutiveFailures = 0

        eventChannel?.close()
        backfillChannel?.close()
        statusTrigger?.close()
        eventChannel = null
        backfillChannel = null
        statusTrigger = null

        scope = null
        s.cancel()

        _status.value = AggregatorStatus(enabled = false, phase = AggregatorPhase.IDLE)
    }

    /**
     * Forward a signer response Intent received by an Activity (e.g. MainActivity.onNewIntent)
     * to the relay-auth signer so the suspended sign() call resumes. Safe to call when no
     * signer is currently installed — the response is just dropped.
     */
    fun deliverSignerResponse(data: Intent) {
        relayAuth.relayAuthSigner?.newResponse(data)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager(context) ?: return
        val cb = AggregatorNetworkCallback(
            context,
            relayClient,
            { networkChangeDetector },
            ::scheduleRelayReset,
            ::cancelPendingRelayReset,
            ::scheduleNetworkEval,
        )
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    @Synchronized
    private fun scheduleRelayReset(reason: String) {
        val s = scope ?: return
        pendingRelayReset?.cancel()
        pendingRelayReset = s.launch {
            delay(NETWORK_SETTLE_MS)
            resetRelayConnections(relayClient, reason)
        }
    }

    @Synchronized
    private fun cancelPendingRelayReset() {
        pendingRelayReset?.cancel()
        pendingRelayReset = null
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        networkEvalJob?.cancel()
        networkEvalJob = null
        cancelPendingRelayReset()
        networkChangeDetector = NetworkChangeDetector()
        val cm = connectivityManager(context) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
            .onFailure { Log.e(TAG, "unregisterNetworkCallback failed", it) }
    }

    private fun scheduleNetworkEval() {
        val s = scope ?: return
        networkEvalJob?.cancel()
        networkEvalJob = s.launch {
            delay(NETWORK_DEBOUNCE_MS)
            evaluateNetworkState()
        }
    }

    @Synchronized
    private fun evaluateNetworkState() {
        if (scope == null) return
        val shouldPause = (Settings.relayAggregatorWifiOnly && isOnMeteredNetwork(context)) ||
            (Settings.relayAggregatorPauseOnLimitedNetwork && isOnLimitedNetwork(context))
        if (shouldPause && !pausedForNetwork) {
            pauseForNetwork()
        } else if (!shouldPause && pausedForNetwork) {
            resumeFromNetwork()
        }
    }

    private fun pauseForNetwork() {
        Log.d(TAG, "Pausing aggregator: network pause condition triggered")
        pausedForNetwork = true
        closeSubscriptionsKeepingSinceCursors()
        publishStatus(phase = AggregatorPhase.PAUSED)
    }

    private fun closeSubscriptionsKeepingSinceCursors() {
        subscriptions.trackedSubIds.forEach { runCatching { relayClient.client.unsubscribe(it) } }
        subscriptions.trackedSubIds.clear()
        subscriptions.activeRelaySubs.clear()
        subscriptions.subscribedRelays.clear()
        subscriptions.connectedRelays.clear()
        subscriptions.taggedSubIds.clear()
    }

    private fun resumeFromNetwork() {
        Log.d(TAG, "Resuming aggregator: network pause condition cleared")
        pausedForNetwork = false
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

    /** Starts, restarts or stops the aggregator after its settings changed. */
    fun onConfigChanged() = onConfigChanged(AppDatabase.getDatabase(context))

    private fun onConfigChanged(database: AppDatabase) {
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
            enabled && pubkey != currentPubkey -> {
                Log.d(TAG, "Aggregator pubkey changed ($currentPubkey -> $pubkey), restarting")
                stop()
                Settings.relayAggregatorLastSync = 0L
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                start(database)
            }
            enabled && running -> {
                scope?.let { relayAuth.installAuthenticatorIfNeeded(it, context, relayClient) }
                evaluateNetworkState()
                if (!pausedForNetwork) {
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

    private suspend fun refreshAndSubscribe(database: AppDatabase): Boolean {
        if (Citrine.isImportingEvents) {
            Log.d(TAG, "Import in progress; skipping refresh")
            return false
        }
        if (pausedForNetwork) {
            Log.d(TAG, "Paused for network condition; skipping refresh")
            return false
        }
        if (!refreshing.compareAndSet(false, true)) {
            Log.d(TAG, "Refresh already running; skipping")
            return false
        }
        val refreshStartMs = System.currentTimeMillis()
        val sinceLastFinish = refreshStartMs - lastRefreshFinishedAt
        if (lastRefreshFinishedAt > 0L && sinceLastFinish < MIN_REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Refresh requested ${sinceLastFinish}ms after last completion; skipping (floor=${MIN_REFRESH_INTERVAL_MS}ms)")
            refreshing.set(false)
            return false
        }
        try {
            runRefresh(database, refreshStartMs)
        } finally {
            lastRefreshFinishedAt = System.currentTimeMillis()
            refreshing.set(false)
        }
        return true
    }

    private suspend fun runRefresh(database: AppDatabase, refreshStartMs: Long) {
        val aggPubkey = Settings.aggregatorPubkey
        val extraRelays = Settings.relayAggregatorExtraRelays
            .mapNotNull { raw -> fetcher.normalizeRemote(raw) }
            .toSet()
        if (aggPubkey.isBlank() && extraRelays.isEmpty()) {
            Log.d(TAG, "No pubkey and no extra relays; nothing to refresh")
            return
        }
        val dao = database.eventDao()

        if (!relayClient.client.isActive()) {
            Log.d(TAG, "Nostr client is not active; connecting")
            relayClient.client.connect()
        }

        val noPubkeyMode = aggPubkey.isBlank()
        Log.d(
            TAG,
            if (noPubkeyMode) {
                "Refresh starting: no-pubkey mode, ${extraRelays.size} relay(s)"
            } else {
                "Refresh starting for $aggPubkey (extraRelays=${extraRelays.size})"
            },
        )
        publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)

        val plan = if (noPubkeyMode) {
            planExtraRelaySubscriptions(extraRelays)
        } else {
            planOwnerSubscriptions(dao, aggPubkey)
        }

        publishStatus(phase = AggregatorPhase.REFRESHING)

        val subscriptionKinds = kindsExceptMetadataAndContactsFetchedWithoutSince()
        val bootstrapSince = subscriptions.computeBootstrapSince()
        subscriptions.reseedCursors(dao, plan, subscriptionKinds, bootstrapSince)
        val newActiveSubs = subscriptions.pushMainSubs(plan.relayToAuthors, subscriptionKinds, bootstrapSince)

        if (!noPubkeyMode && Settings.relayAggregatorIncludeTagged) {
            subscriptions.pushTaggedSubs(newActiveSubs, aggPubkey, plan.relayLists.of(aggPubkey), subscriptionKinds, bootstrapSince)
        } else {
            subscriptions.dropTaggedSubs()
        }

        val newSubscribedRelays = subscriptions.commit(newActiveSubs)
        finishRefresh(database, refreshStartMs, newSubscribedRelays.size, noPubkeyMode, aggPubkey, plan.ownerFollows)
    }

    private fun kindsExceptMetadataAndContactsFetchedWithoutSince(): List<Int> = Settings.relayAggregatorKinds.toList() -
        MetadataEvent.KIND - ContactListEvent.KIND

    private fun finishRefresh(
        database: AppDatabase,
        refreshStartMs: Long,
        subscribedRelayCount: Int,
        noPubkeyMode: Boolean,
        aggPubkey: String,
        ownerFollows: Set<String>,
    ) {
        Settings.relayAggregatorLastSync = TimeUtils.now()
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
        Log.d(
            TAG,
            "Refresh complete in ${System.currentTimeMillis() - refreshStartMs}ms: " +
                "$authorCount authors, $subscribedRelayCount relays, " +
                "${subscriptions.trackedSubIds.size} tracked subIds, " +
                "noPubkey=$noPubkeyMode, tagged=${Settings.relayAggregatorIncludeTagged}",
        )
        publishStatus(phase = AggregatorPhase.LISTENING)

        if (!noPubkeyMode && !Settings.strangerContactListsPruned) {
            scope?.launch {
                try {
                    pruneStrangerContactLists(context, database, aggPubkey, ownerFollows)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Contact list cleanup failed", e)
                }
            }
        }
    }

    private fun planExtraRelaySubscriptions(extraRelays: Set<NormalizedRelayUrl>): SubscriptionPlan {
        authorCount = 0
        val relayToAuthors = mutableMapOf<NormalizedRelayUrl, MutableSet<String>>()
        extraRelays.forEach { relay ->
            relayToAuthors[relay] = anyAuthor()
        }
        return SubscriptionPlan(relayToAuthors, RelayLists(emptyMap(), emptyMap()), scopedAuthors = emptySet(), ownerFollows = emptySet())
    }

    private fun anyAuthor(): MutableSet<String> = mutableSetOf()

    private suspend fun planOwnerSubscriptions(dao: EventDao, aggPubkey: String): SubscriptionPlan {
        val nowSec = TimeUtils.now()
        val refreshIntervalSec = Settings.relayAggregatorRefreshMinutes.coerceAtLeast(1) * 60L
        val cacheFresh = Settings.relayAggregatorLastSync > 0L &&
            (nowSec - Settings.relayAggregatorLastSync) < refreshIntervalSec
        Log.d(
            TAG,
            if (cacheFresh) {
                "Cache fresh (last sync ${nowSec - Settings.relayAggregatorLastSync}s ago < " +
                    "${refreshIntervalSec}s); reusing cached contact/mute/metadata"
            } else {
                "Cache stale; performing full network refresh"
            },
        )

        runCatching { muteList.refreshMuteList(dao, aggPubkey, skipNetworkIfCached = cacheFresh) }
            .onFailure { Log.e(TAG, "refreshMuteList failed", it) }

        val contactListMs = System.currentTimeMillis()
        val contactList = fetcher.loadOrBootstrapContactList(dao, aggPubkey, skipNetworkIfCached = cacheFresh)
        val follows = contactList?.verifiedFollowKeySet() ?: emptySet()
        val authors = (follows + aggPubkey).toSet()
        followedAuthors = authors
        authorCount = authors.size
        Log.d(
            TAG,
            "Contact list resolved in ${System.currentTimeMillis() - contactListMs}ms: " +
                "${follows.size} follows (+ self) for $aggPubkey (createdAt=${contactList?.createdAt ?: "none"})",
        )

        val indexers = fetcher.indexerRelaysOrDefaults()
        val sources = fetcher.aggregatorSourceRelays()

        val relayLists = resolveRelayLists(relayClient, fetcher, dao, authors, indexers)
        val relayToAuthors = buildOutboxMap(fetcher, authors, relayLists, sources)

        if (!cacheFresh) {
            fetchOwnerMetadata(relayClient, fetcher, authors, relayLists, indexers, sources)
        }
        return SubscriptionPlan(relayToAuthors, relayLists, scopedAuthors = authors, ownerFollows = follows)
    }

    private fun publishStatus(phase: AggregatorPhase) {
        _status.value = AggregatorStatus(
            enabled = scope != null,
            phase = phase,
            authors = authorCount,
            relaysConfigured = subscriptions.subscribedRelays.size,
            relaysConnected = subscriptions.connectedRelays.size,
            eventsReceived = eventsReceived.get(),
            lastRefreshEpoch = Settings.relayAggregatorLastSync,
        )
    }

    private fun refreshStatusCounters() {
        statusDirty.set(true)
        statusTrigger?.trySend(Unit)
    }

    private fun flushPendingStatus() {
        if (!statusDirty.compareAndSet(true, false)) return
        val current = _status.value
        _status.value = current.copy(
            relaysConfigured = subscriptions.subscribedRelays.size,
            relaysConnected = subscriptions.connectedRelays.size,
            eventsReceived = eventsReceived.get(),
        )
    }

    private class AggregatorListener : RelayConnectionListener {
        override suspend fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            val subscribedRelays = subscriptions.subscribedRelays
            val connectedRelays = subscriptions.connectedRelays
            if (subscribedRelays.contains(relay.url)) {
                if (connectedRelays.add(relay.url)) {
                    Log.d(TAG, "Relay live (${connectedRelays.size}/${subscribedRelays.size}): ${relay.url.url}")
                    refreshStatusCounters()
                }
            }
            if (msg is EventMessage && subscriptions.trackedSubIds.contains(msg.subId)) {
                val ev = msg.event
                subscriptions.lastEventByPubkey.merge(ev.pubKey, ev.createdAt) { a, b -> maxOf(a, b) }
                val n = eventsReceived.incrementAndGet()
                if (isProgressLogCount(n)) {
                    Log.d(
                        TAG,
                        "Events received: $n (${connectedRelays.size}/${subscribedRelays.size} relays connected)",
                    )
                }
                refreshStatusCounters()
                val mismatchReason = subscriptions.filterMismatchReason(relay.url, msg.subId, ev)
                if (mismatchReason != null) {
                    val d = droppedEvents.incrementAndGet()
                    if (isProgressLogCount(d)) {
                        Log.d(TAG, "Dropping event ${ev.id} (kind ${ev.kind}) from ${relay.url.url} sub ${msg.subId}: $mismatchReason (total dropped: $d)")
                    }
                    return
                }
                eventChannel?.trySend(ev)
                backfillOnFirstSight(ev.pubKey)
            }
        }

        private fun isProgressLogCount(n: Long): Boolean = n == 1L || n % PROGRESS_LOG_EVERY == 0L

        private fun backfillOnFirstSight(pubkey: String) {
            if (backfilledPubkeys.get(pubkey) == null) {
                backfilledPubkeys.put(pubkey, true)
                backfillChannel?.trySend(pubkey)
            }
        }

        @Suppress("NoComments")
        override fun onDisconnected(relay: IRelayClient) {
            val connectedRelays = subscriptions.connectedRelays
            // Quartz's NostrClient.onConnected() calls syncFilters() on reconnect, which
            // re-issues every REQ stored in desiredSubs for that relay. We don't re-send
            // anything from here — doing so on every disconnect / onCannotConnect turns
            // unreachable relays into a resub storm.
            if (connectedRelays.remove(relay.url)) {
                Log.d(TAG, "Disconnected from ${relay.url.url} (now ${connectedRelays.size}/${subscriptions.subscribedRelays.size})")
                refreshStatusCounters()
            }
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            if (subscriptions.subscribedRelays.contains(relay.url)) {
                Log.d(TAG, "Cannot connect to ${relay.url.url}: $errorMessage")
            }
            if (subscriptions.connectedRelays.remove(relay.url)) {
                refreshStatusCounters()
            }
        }
    }
}
