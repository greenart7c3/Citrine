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
internal const val MAX_AUTHORS_PER_SUB = 100

internal const val BATCH_FETCH_TIMEOUT_MS = 15_000L

// Floor on time between two refreshes regardless of trigger (loop tick, network resume,
// config change). Prevents back-to-back full bootstraps from a flapping connection.
private const val MIN_REFRESH_INTERVAL_MS = 30_000L

// Cap on how many configured intervals we'll skip after consecutive refresh failures.
private const val MAX_FAILURE_BACKOFF_MULTIPLIER = 8

// Bounded buffer for events handed off from the relay listener to the consumer
// coroutine. DROP_OLDEST means a chatty relay can't OOM the IO pool; the next refresh
// re-queries with a `since` derived from the DB so any dropped event is recoverable.
private const val EVENT_CHANNEL_CAPACITY = 256

// How long to wait after the last connectivity callback before re-evaluating the
// network state. Collapses bursts of `onCapabilitiesChanged` during signal flapping
// into a single evaluation.
private const val NETWORK_DEBOUNCE_MS = 2_000L

// How long to let a network change settle before rebuilding the relay sockets.
private const val NETWORK_SETTLE_MS = 1_000L

// Cap on how often counter-only status updates (relay count, event count) are pushed
// through `_status`. Phase changes (BOOTSTRAPPING/REFRESHING/LISTENING/PAUSED) still
// emit immediately. Without this, the StateFlow fires on every received event and
// every Compose collector recomposes hundreds of times per second on the UI thread.
private const val STATUS_EMIT_INTERVAL_MS = 500L

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

    // Pubkeys we've already considered for opportunistic kind-0 / kind-3 backfill in this
    // session. The LruCache bound trades a rare duplicate backfill (idempotent: the DAO
    // existence check inside backfillUserIfMissing skips the network call) for never
    // letting the seen-set grow with every stranger surfaced by the tagged subs.
    private val backfilledPubkeys = LruCache<String, Boolean>(10_000)

    private val eventsReceived = AtomicLong(0)

    // Authors whose contact list the owner actually consumes: their own follows plus
    // themselves. Contact lists of strangers surfaced by the tagged subs are no longer
    // backfilled — they are the largest events on the network, kind 3 is in
    // [Settings.preservedKindsFromDeletion] so retention never prunes them, and they are
    // what drove the REQ path into an OutOfMemoryError.
    @Volatile private var followedAuthors: Set<String> = emptySet()

    @Volatile private var authorCount: Int = 0

    // Pubkey the currently-running aggregator was started for. Used by onConfigChanged
    // to detect identity changes and trigger a full restart.
    @Volatile private var currentPubkey: String? = null

    // True while subscriptions are torn down because the active network triggered a pause
    // condition — either it is metered with [Settings.relayAggregatorWifiOnly] enabled, or
    // it is restricted/limited with [Settings.relayAggregatorPauseOnLimitedNetwork] enabled.
    // The refresh loop checks this flag and skips its body until [resumeFromNetwork] resets
    // it.
    @Volatile private var pausedForNetwork: Boolean = false

    // The database the aggregator was started with — saved so the network callback can
    // trigger a refresh on resume without the caller threading it through.
    @Volatile private var database: AppDatabase? = null

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Tracks the default network so a switch (Wi-Fi <-> mobile, VPN up/down, regaining
    // internet access) rebuilds the relay sockets instead of leaving them on dead routes.
    @Volatile private var networkChangeDetector = NetworkChangeDetector()

    // Settle delay for [resetRelayConnections]; replaced by every network change.
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

    // Debounced re-evaluation of network state. Cancelled and rescheduled by every
    // ConnectivityManager callback so a burst of changes only triggers one evaluation.
    @Volatile private var networkEvalJob: Job? = null

    // Hand-off queues from AggregatorListener to single consumer coroutines. Replacing
    // per-event `scope.launch { ... }` cuts coroutine creation under heavy event load.
    @Volatile private var eventChannel: Channel<Event>? = null

    @Volatile private var backfillChannel: Channel<String>? = null

    // Wall-clock time of the last refreshAndSubscribe completion. Used to throttle
    // back-to-back refresh requests (start, network resume, config change).
    @Volatile private var lastRefreshFinishedAt: Long = 0L

    // Number of consecutive refresh failures. Multiplies the loop sleep by 2^n up to
    // MAX_FAILURE_BACKOFF_MULTIPLIER, then resets on success.
    @Volatile private var consecutiveFailures: Int = 0

    private val droppedEvents = AtomicLong(0)

    // Set by refreshStatusCounters when relay counts or eventsReceived change; consumed
    // by the demand-driven emitter started in `start()` to push at most one StateFlow
    // update per STATUS_EMIT_INTERVAL_MS while parking completely idle with zero wakeups
    // whenever nothing is changing.
    private val statusDirty = AtomicBoolean(false)

    @Volatile private var statusTrigger: Channel<Unit>? = null

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
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
        scope = newScope

        val newEventChannel = Channel<Event>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        val newBackfillChannel = Channel<String>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        val newStatusTrigger = Channel<Unit>(Channel.CONFLATED)
        eventChannel = newEventChannel
        backfillChannel = newBackfillChannel
        statusTrigger = newStatusTrigger

        // Single consumer for inbound events: persist sequentially instead of spawning a
        // coroutine per event in onIncomingMessage.
        launchEventConsumer(newScope, newEventChannel, database)

        // Single consumer for opportunistic kind-0 / kind-3 backfill of newly-seen authors.
        launchBackfillConsumer(newScope, newBackfillChannel, database)

        // Demand-driven flusher for counter-only status updates. Parks completely idle
        // with zero wakeups when nothing is arriving, and coalesces a burst of
        // refreshStatusCounters() calls into at most one StateFlow emission per
        // STATUS_EMIT_INTERVAL_MS so Compose collectors don't recompose per event.
        launchStatusFlusher(newScope, newStatusTrigger)

        val newListener = AggregatorListener()
        listener = newListener
        relayClient.client.addConnectionListener(newListener)

        relayAuth.installAuthenticatorIfNeeded(newScope, context, relayClient)

        registerNetworkCallback()
        // Initial check: if we're already on a metered network and the user wants
        // wifi-only, start in the paused state instead of opening subs.
        evaluateNetworkState()

        if (!pausedForNetwork) {
            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)
        }

        // Warm the mute-list cache from the local DB before the refresh loop kicks off the
        // network fetch. Ensures the first events admitted after start() respect any mute
        // entries we already saved on a previous run; no network round-trip on this path.
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
                    // Owner's mute-list update arriving live (kind 10000) refreshes the in-memory
                    // sets so subsequent events use the new list without waiting for the next
                    // periodic refresh tick.
                    if (ev.kind == 10000 && ev.pubKey == Settings.aggregatorPubkey && ev.createdAt > muteList.muteListSeenAt) {
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
                        if (!refreshAndSubscribe(database)) {
                            // Skipped, e.g. a cancelled refresh from before a restart still holds
                            // `refreshing` or just stamped the floor. Sleeping the full interval
                            // here left the status on "Fetching…" for an hour, so retry soon.
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

        // The authenticator's internal client listener stays registered with NostrClient
        // until destroy() is called, so explicitly tear it down here in addition to
        // dropping the references. A subsequent start() will install a fresh one against
        // the new scope.
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

    /**
     * Network changes arrive as a burst of callbacks (the new network, then its
     * capabilities as validation completes); wait for it to settle so the relays are
     * rebuilt once, on the final network.
     */
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

    /**
     * Reconciles the current network state against the wifi-only and pause-on-limited
     * settings, pausing or resuming the aggregator accordingly. Cheap to call repeatedly;
     * only acts when the desired state diverges from the actual one.
     */
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
        // Tear down active subscriptions so we stop receiving events. Keep
        // [lastEventByPubkey] and [currentPubkey] in place — when we resume, the next
        // refresh uses them to compute tight per-chunk `since` values.
        subscriptions.trackedSubIds.forEach { runCatching { relayClient.client.unsubscribe(it) } }
        subscriptions.trackedSubIds.clear()
        subscriptions.activeRelaySubs.clear()
        subscriptions.subscribedRelays.clear()
        subscriptions.connectedRelays.clear()
        subscriptions.taggedSubIds.clear()
        publishStatus(phase = AggregatorPhase.PAUSED)
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
                // Identity changed: drop every existing subscription and rebuild from
                // scratch. Reset lastSync so the new user gets the full cold-start
                // window. The contact list is always re-fetched on every refresh
                // (no cache short-circuit), so the new user's follows are picked
                // up from the network on the first refresh after restart.
                Log.d(TAG, "Aggregator pubkey changed ($currentPubkey -> $pubkey), restarting")
                stop()
                Settings.relayAggregatorLastSync = 0L
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                start(database)
            }
            enabled && running -> {
                // The AUTH toggle / signer identity may have changed. Reinstall the
                // authenticator against the running scope so the new settings take effect
                // without a full restart.
                scope?.let { relayAuth.installAuthenticatorIfNeeded(it, context, relayClient) }
                // The wifi-only toggle may have just changed; re-evaluate before
                // dispatching a refresh so a flip to any network-based pause condition
                // doesn't kick off a doomed refresh that we'd immediately tear down.
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

    /** Returns false when the refresh was skipped without running, so the loop can retry soon. */
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

        // When no pubkey is configured, the aggregator becomes a plain relay listener:
        // subscribe to every extra relay with the configured kinds and the sync window,
        // no author filter, no NIP-65 lookups, no tagged sub. This path uses the same
        // diff-vs-activeRelaySubs + cleanup logic as the pubkey path by representing
        // each relay as a single empty-author "chunk".
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

        // Kinds 0 (metadata) and 3 (contact list) are fetched via dedicated unfiltered
        // paths above (loadOrBootstrapContactList, batchFetchMetadata) — exclude them
        // here so they aren't also subscribed with a `since` ceiling on the main and
        // tagged subs, which would risk masking newer copies behind the cursor.
        val subscriptionKinds = Settings.relayAggregatorKinds.toList() -
            MetadataEvent.KIND - ContactListEvent.KIND
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

        // One-shot cleanup of contact lists stored for authors the owner does not follow.
        // Runs on the aggregator scope so a large delete never delays the subscriptions
        // that were just opened.
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
            // Empty author set is a marker the subscribe loop recognizes to
            // issue a filter without the `authors` field.
            relayToAuthors[relay] = mutableSetOf()
        }
        return SubscriptionPlan(relayToAuthors, RelayLists(emptyMap(), emptyMap()), scopedAuthors = emptySet(), ownerFollows = emptySet())
    }

    private suspend fun planOwnerSubscriptions(dao: EventDao, aggPubkey: String): SubscriptionPlan {
        // When a successful refresh happened recently (within one refresh interval),
        // treat the DB cache as authoritative for the rarely-changing owner data
        // (contact list, mute list, metadata) and skip the proactive network fetches.
        // This is what makes an app restart or a network change cheap: subscriptions
        // are rebuilt from cache instead of re-pulling everything from indexer relays.
        // NIP-65 (below) is intentionally left cache-first-but-fetch-misses since it
        // feeds relay routing. The periodic loop fires after the interval elapses, so
        // it sees cacheFresh == false and still does a full network refresh that picks
        // up new follows.
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

        // Refresh the owner's NIP-51 mute list before opening the main subscriptions.
        // The public portion is applied synchronously; the (optional) private portion
        // resolves on its own coroutine via the signer so a slow Amber prompt does not
        // hold up the rest of the refresh.
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

        val indexers = fetcher.indexerRelays()
        val sources = fetcher.aggregatorSourceRelays()

        val relayLists = resolveRelayLists(relayClient, fetcher, dao, authors, indexers)
        val relayToAuthors = buildOutboxMap(fetcher, authors, relayLists, sources)

        // Metadata (kind 0) is only stored, never used to build subscriptions, so
        // skip both the relay-map construction and the network fetch when the cache is
        // fresh. Newly-seen authors on the live stream still get their metadata via
        // backfillUserIfMissing().
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

    // Marks the counters as needing publication and wakes the demand-driven emitter.
    // The emitter coalesces bursts over STATUS_EMIT_INTERVAL_MS and parks with zero
    // wakeups whenever nothing is arriving. Cheap enough to call on the relay listener
    // thread for every received event.
    private fun refreshStatusCounters() {
        statusDirty.set(true)
        statusTrigger?.trySend(Unit)
    }

    // Publishes the current counter snapshot to `_status` if any counter changed since
    // the last emission. Phase changes go through `publishStatus` directly and bypass
    // the throttle so UI/notification reflect transitions promptly.
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
                // Track lastSeen by author so the next refresh can compute a tighter
                // per-chunk `since`. Update for every received event — events from
                // tagged subs (random pubkeys) cost a bit of memory but are harmless;
                // events from main subs are exactly what we want to record.
                subscriptions.lastEventByPubkey.merge(ev.pubKey, ev.createdAt) { a, b -> maxOf(a, b) }
                val n = eventsReceived.incrementAndGet()
                // Periodic progress heartbeat so we can see the stream without per-event spam.
                if (n == 1L || n % 100L == 0L) {
                    Log.d(
                        TAG,
                        "Events received: $n (${connectedRelays.size}/${subscribedRelays.size} relays connected)",
                    )
                }
                refreshStatusCounters()
                val mismatchReason = subscriptions.filterMismatchReason(relay.url, msg.subId, ev)
                if (mismatchReason != null) {
                    val d = droppedEvents.incrementAndGet()
                    if (d == 1L || d % 100L == 0L) {
                        Log.d(TAG, "Dropping event ${ev.id} (kind ${ev.kind}) from ${relay.url.url} sub ${msg.subId}: $mismatchReason (total dropped: $d)")
                    }
                    return
                }
                // Hand the event off to the consumer coroutine instead of launching a fresh
                // coroutine per event. trySend is non-blocking; the bounded channel is
                // configured with DROP_OLDEST so a chatty relay degrades gracefully.
                eventChannel?.trySend(ev)
                // First time we see this author in the live stream, kick off a one-shot
                // backfill of their kind 0 / kind 3 from the indexer relays so unknown users
                // surfaced via the tagged sub get a profile + follow list. The DAO check
                // inside backfillUserIfMissing skips the network call when both are already
                // cached, so authors covered by the periodic batch fetch are a no-op.
                if (backfilledPubkeys.get(ev.pubKey) == null) {
                    backfilledPubkeys.put(ev.pubKey, true)
                    backfillChannel?.trySend(ev.pubKey)
                }
            }
        }

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
