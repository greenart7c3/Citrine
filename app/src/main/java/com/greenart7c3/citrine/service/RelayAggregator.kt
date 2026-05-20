package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.PubkeyTimestamp
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
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
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

    private val FALLBACK_OUTBOX_RELAYS = listOf(
        RelayUrlNormalizer.normalize("wss://relay.damus.io/"),
        RelayUrlNormalizer.normalize("wss://nos.lol/"),
        RelayUrlNormalizer.normalize("wss://relay.primal.net/"),
    )

    // Keep well under the per-filter author cap that stricter relays enforce (often 100–200).
    private const val MAX_AUTHORS_PER_SUB = 100

    // Per-author cap on how many of the author's NIP-65 write relays we'll actually
    // subscribe to. Lower = fewer concurrent WebSockets and less battery drain; higher
    // = better redundancy if any one relay drops events. The greedy picker (capRelaysPerAuthor)
    // prefers relays that already cover other authors so the chosen set converges to a
    // small number of well-shared relays even with a large follow list.
    private const val MAX_RELAYS_PER_AUTHOR = 3
    private const val DEFAULT_COLD_START_WINDOW_SEC = 24L * 60L * 60L
    private const val OVERLAP_SEC = 5L * 60L
    private const val BATCH_FETCH_TIMEOUT_MS = 15_000L

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

    // Cap on how often counter-only status updates (relay count, event count) are pushed
    // through `_status`. Phase changes (BOOTSTRAPPING/REFRESHING/LISTENING/PAUSED) still
    // emit immediately. Without this, the StateFlow fires on every received event and
    // every Compose collector recomposes hundreds of times per second on the UI thread.
    private const val STATUS_EMIT_INTERVAL_MS = 500L

    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var listener: AggregatorListener? = null

    // Per-relay bookkeeping of active subscriptions so subIds stay stable across refreshes.
    // Quartz re-sends every desired REQ on reconnect via syncFilters(), so we don't track
    // these for resub purposes — only for preserving the subId set across refresh cycles
    // and for diagnosing which authors / since each chunk covers.
    private val activeRelaySubs: ConcurrentHashMap<NormalizedRelayUrl, MutableList<ChunkSubscription>> =
        ConcurrentHashMap()
    private val trackedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Latest event createdAt observed per pubkey. Seeded from the local DB at the start of
    // every refresh and updated as events arrive on tracked subs. Used to compute a per-chunk
    // `since` so a newly-followed pubkey gets backfilled while caught-up authors don't refetch
    // their full window.
    private val lastEventByPubkey: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    // Pubkeys we've already considered for opportunistic kind-0 / kind-3 backfill in this
    // session. Add()-on-first-sight guarantees we only spawn one backfill coroutine per pubkey
    // even when many events arrive in quick succession.
    private val backfilledPubkeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val subscribedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
    private val connectedRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()

    // .onion relays that returned "SOCKS: Connection refused" — either Tor isn't running
    // or the hidden service is gone. Quartz would otherwise keep retrying on every refresh
    // and on its own internal reconnect, burning battery against an address that won't
    // resolve until the user restarts the aggregator or Tor comes back. Cleared in stop().
    private val skippedOnionRelays: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()

    private val eventsReceived = AtomicLong(0)

    // NIP-51 (kind 10000) mute list — author pubkeys to drop and content words to drop.
    // Words are pre-lowercased so the hot-path check can match against a single lowercase
    // copy of the event content without per-word allocation. Both sets are swapped as a
    // pair so a partial load never produces an inconsistent view.
    @Volatile private var mutedPubkeys: Set<String> = emptySet()

    @Volatile private var mutedWords: Set<String> = emptySet()

    // `createdAt` of the last kind-10000 we parsed. Lets the live-event refresh path
    // (when the aggregator's own kind-10000 arrives via the regular subscription) skip
    // a redundant decryption round-trip when the event is older than what we already have.
    @Volatile private var muteListSeenAt: Long = 0L

    // Off-main job running the optional NIP-04/44 decryption of the private mute entries.
    // Held so stop() / pubkey-change can cancel an in-flight decrypt before the signer
    // resumes against a stale identity.
    @Volatile private var muteRefreshJob: Job? = null

    @Volatile private var authorCount: Int = 0

    @Volatile private var taggedSubId: String? = null

    // Pubkey the currently-running aggregator was started for. Used by onConfigChanged
    // to detect identity changes and trigger a full restart.
    @Volatile private var currentPubkey: String? = null

    // True while subscriptions are torn down because the active network is metered and
    // [Settings.relayAggregatorWifiOnly] is enabled. The refresh loop checks this flag and
    // skips its body until [resumeFromMetered] resets it.
    @Volatile private var pausedForMetered: Boolean = false

    // The database the aggregator was started with — saved so the network callback can
    // trigger a refresh on resume without the caller threading it through.
    @Volatile private var database: AppDatabase? = null

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // RelayAuthenticator listens for NIP-42 AUTH challenges from upstream relays and signs
    // a kind-22242 event using the external signer. Held so the start/stop lifecycle ties
    // the authenticator's lifespan to the aggregator's scope; it stops listening on cancel.
    @Volatile private var authenticator: RelayAuthenticator? = null

    // External signer used by the authenticator. Held so [deliverSignerResponse] can route
    // an Intent result received by MainActivity into the same instance whose suspended
    // sign() call is awaiting it.
    @Volatile private var relayAuthSigner: NostrSignerExternal? = null

    // Identity (pubkey + packageName) of the signer the currently-installed authenticator
    // was built for. Used by installAuthenticatorIfNeeded to skip a rebuild when the
    // signer hasn't changed — every onConfigChanged call would otherwise tear down and
    // recreate the authenticator, briefly leaving zero listeners and discarding any
    // in-flight AUTH state.
    @Volatile private var installedAuthIdentity: Pair<String, String>? = null

    // Cache of (relay → last challenge string, signed AUTH event) so a relay re-sending
    // the same challenge — common on reconnect, and what was driving the duplicate Amber
    // prompts — returns the previously-signed event without re-invoking the signer.
    private val signedAuthCache: ConcurrentHashMap<String, Pair<String, RelayAuthEvent>> = ConcurrentHashMap()

    // Activity-scoped Intent launcher set by MainActivity. Uses registerForActivityResult
    // under the hood, which preserves the caller's identity on the intent — Amber relies
    // on that callingPackage to identify which app is asking, and a plain Application-
    // context startActivity() makes it null.
    @Volatile private var activityLauncher: ((Intent) -> Unit)? = null

    /**
     * Called by [MainActivity] in onCreate to provide an ActivityResultLauncher-backed
     * way to launch the signer intent. The aggregator's foreground signer path goes
     * through this when invoked while the activity is alive.
     */
    fun registerActivityLauncher(launcher: (Intent) -> Unit) {
        activityLauncher = launcher
    }

    fun unregisterActivityLauncher() {
        activityLauncher = null
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
    // by the periodic emitter started in `start()` to push at most one StateFlow update
    // per STATUS_EMIT_INTERVAL_MS.
    private val statusDirty = AtomicBoolean(false)

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

        val newEventChannel = Channel<Event>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        val newBackfillChannel = Channel<String>(EVENT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        eventChannel = newEventChannel
        backfillChannel = newBackfillChannel

        // Single consumer for inbound events: persist sequentially instead of spawning a
        // coroutine per event in onIncomingMessage.
        newScope.launch {
            for (ev in newEventChannel) {
                try {
                    if (shouldDropForMute(ev)) {
                        droppedEvents.incrementAndGet()
                        continue
                    }
                    CustomWebSocketService.server?.innerProcessEvent(ev, null, fromAggregator = true)
                    // Owner's mute-list update arriving live (kind 10000) refreshes the in-memory
                    // sets so subsequent events use the new list without waiting for the next
                    // periodic refresh tick.
                    if (ev.kind == 10000 && ev.pubKey == Settings.aggregatorPubkey && ev.createdAt > muteListSeenAt) {
                        scheduleMuteListRefresh(database)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "innerProcessEvent failed", e)
                }
            }
        }

        // Single consumer for opportunistic kind-0 / kind-3 backfill of newly-seen authors.
        newScope.launch {
            for (pubkey in newBackfillChannel) {
                try {
                    backfillUserIfMissing(database.eventDao(), pubkey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "backfill for $pubkey failed", e)
                }
            }
        }

        // Periodic flusher for counter-only status updates. Coalesces a burst of
        // refreshStatusCounters() calls (one per received event) into at most one
        // StateFlow emission per STATUS_EMIT_INTERVAL_MS so Compose collectors don't
        // recompose on the UI thread for every event.
        newScope.launch {
            while (isActive) {
                delay(STATUS_EMIT_INTERVAL_MS)
                flushPendingStatus()
            }
        }

        val newListener = AggregatorListener()
        listener = newListener
        Citrine.instance.client.addConnectionListener(newListener)

        installAuthenticatorIfNeeded(newScope)

        registerNetworkCallback()
        // Initial check: if we're already on a metered network and the user wants
        // wifi-only, start in the paused state instead of opening subs.
        evaluateNetworkState()

        if (!pausedForMetered) {
            publishStatus(phase = AggregatorPhase.BOOTSTRAPPING)
        }

        // Warm the mute-list cache from the local DB before the refresh loop kicks off the
        // network fetch. Ensures the first events admitted after start() respect any mute
        // entries we already saved on a previous run; no network round-trip on this path.
        if (Settings.aggregatorPubkey.isNotBlank()) {
            newScope.launch {
                try {
                    loadCachedMuteListFromDb(database.eventDao(), Settings.aggregatorPubkey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Warm mute-list load failed", e)
                }
            }
        }

        newScope.launch {
            while (isActive) {
                if (!pausedForMetered) {
                    try {
                        refreshAndSubscribe(database)
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
        listener?.let { Citrine.instance.client.removeConnectionListener(it) }
        listener = null

        // The authenticator's internal client listener stays registered with NostrClient
        // until destroy() is called, so explicitly tear it down here in addition to
        // dropping the references. A subsequent start() will install a fresh one against
        // the new scope.
        authenticator?.destroy()
        authenticator = null
        relayAuthSigner = null
        installedAuthIdentity = null
        signedAuthCache.clear()

        unregisterNetworkCallback()

        trackedSubIds.forEach {
            runCatching { Citrine.instance.client.unsubscribe(it) }
        }
        trackedSubIds.clear()
        activeRelaySubs.clear()
        subscribedRelays.clear()
        connectedRelays.clear()
        skippedOnionRelays.clear()
        lastEventByPubkey.clear()
        muteRefreshJob?.cancel()
        muteRefreshJob = null
        mutedPubkeys = emptySet()
        mutedWords = emptySet()
        muteListSeenAt = 0L
        backfilledPubkeys.clear()
        eventsReceived.set(0)
        droppedEvents.set(0)
        statusDirty.set(false)
        authorCount = 0
        taggedSubId = null
        currentPubkey = null
        pausedForMetered = false
        database = null
        lastRefreshFinishedAt = 0L
        consecutiveFailures = 0

        eventChannel?.close()
        backfillChannel?.close()
        eventChannel = null
        backfillChannel = null

        scope = null
        s.cancel()

        _status.value = AggregatorStatus(enabled = false, phase = AggregatorPhase.IDLE)
    }

    /**
     * Construct a [RelayAuthenticator] bound to [scope] when a signer identity is configured.
     * Re-entrant: drops a previously installed authenticator before installing a new one.
     * No-op when the signer pubkey/packageName is missing — relays that send AUTH challenges
     * will simply not get a response, which matches the prior behavior.
     *
     * The signer is configured with both the ContentResolver (background signing path, used
     * when Amber has stored an auto-approve for kind 22242) and a foreground intent launcher
     * (fallback that pops the Amber UI from the application context when no auto-approve is
     * available). Without the foreground launcher, sign() throws
     * `RunningOnBackgroundWithoutAutomaticPermissionException` the first time Amber needs
     * manual approval.
     */
    private fun installAuthenticatorIfNeeded(scope: CoroutineScope) {
        val pubkey = Settings.aggregatorSignerPubkey
        val pkg = Settings.aggregatorSignerPackageName

        // Skip the tear-down/rebuild if the signer identity matches what we already have
        // installed. onConfigChanged fires for every settings tweak (kind add, refresh
        // interval, etc.); without this guard each one would briefly leave the relay
        // unprotected and discard in-flight AUTH state.
        val desired = if (pubkey.isBlank() || pkg.isBlank()) null else (pubkey to pkg)
        if (authenticator != null && installedAuthIdentity == desired) return

        // Always tear down the previous authenticator before installing a new one — its
        // internal client listener stays registered with NostrClient until destroy() runs,
        // and a second authenticator with the same listener would answer the same AUTH
        // challenge twice.
        authenticator?.destroy()
        authenticator = null
        relayAuthSigner = null
        installedAuthIdentity = null

        if (desired == null) return
        val signer = NostrSignerExternal(pubkey, pkg, Citrine.instance.contentResolver)
        // Lets the signer fall back to Amber's UI when the background ContentResolver path
        // can't auto-approve. The launcher routes through MainActivity's
        // ActivityResultLauncher (set via [registerActivityLauncher]), which preserves
        // callingPackage on the intent and feeds Amber's reply back into this signer via
        // [deliverSignerResponse] so the suspended sign() resumes.
        signer.registerForegroundLauncher { intent ->
            // Must go through the ActivityResultLauncher MainActivity registered with us —
            // that's what populates Intent.callingPackage so Amber can authenticate the
            // caller. A bare activity.startActivity() leaves callingPackage null and
            // Amber rejects. When MainActivity isn't alive, the foreground path can't
            // run; the user must open Citrine once so onCreate registers the launcher,
            // and from then on the ContentResolver background path also works thanks to
            // Amber's stored auto-approve.
            val launcher = activityLauncher
            if (launcher == null) {
                Log.w(TAG, "No Activity launcher registered; open Citrine once to authorize the signer")
                return@registerForegroundLauncher
            }
            try {
                intent.`package` = pkg
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launcher(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch signer intent via Activity launcher", e)
            }
        }
        relayAuthSigner = signer
        installedAuthIdentity = desired
        signedAuthCache.clear()
        Log.d(TAG, "Installing RelayAuthenticator for $pubkey via $pkg")
        authenticator = RelayAuthenticator(Citrine.instance.client, scope) { template ->
            val relay = template.tags.firstOrNull { it.size > 1 && it[0] == "relay" }?.get(1)
            val challenge = template.tags.firstOrNull { it.size > 1 && it[0] == "challenge" }?.get(1)
            if (relay != null && challenge != null) {
                val cached = signedAuthCache[relay]
                if (cached != null && cached.first == challenge) {
                    Log.d(TAG, "Reusing cached AUTH for $relay (challenge unchanged)")
                    return@RelayAuthenticator listOf(cached.second)
                }
            }
            try {
                val signed = signer.sign(template)
                if (relay != null && challenge != null) {
                    signedAuthCache[relay] = challenge to signed
                }
                listOf(signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign AUTH challenge", e)
                emptyList()
            }
        }
    }

    /**
     * Forward a signer response Intent received by an Activity (e.g. MainActivity.onNewIntent)
     * to the relay-auth signer so the suspended sign() call resumes. Safe to call when no
     * signer is currently installed — the response is just dropped.
     */
    fun deliverSignerResponse(data: Intent) {
        relayAuthSigner?.newResponse(data)
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
            override fun onAvailable(network: Network) = scheduleNetworkEval()
            override fun onLost(network: Network) = scheduleNetworkEval()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkEval()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.e(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        networkEvalJob?.cancel()
        networkEvalJob = null
        val cm = connectivityManager() ?: return
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
        subscribedRelays.clear()
        connectedRelays.clear()
        taggedSubId = null
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
            enabled && pubkey != currentPubkey -> {
                // Identity changed: drop every existing subscription and rebuild from
                // scratch. Reset lastSync so the new user gets the full cold-start
                // window. The contact list is always re-fetched on every refresh
                // (no cache short-circuit), so the new user's follows are picked
                // up from the network on the first refresh after restart.
                Log.d(TAG, "Aggregator pubkey changed ($currentPubkey -> $pubkey), restarting")
                stop()
                Settings.relayAggregatorLastSync = 0L
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
                start(database)
            }
            enabled && running -> {
                // The AUTH toggle / signer identity may have changed. Reinstall the
                // authenticator against the running scope so the new settings take effect
                // without a full restart.
                scope?.let { installAuthenticatorIfNeeded(it) }
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
        val sinceLastFinish = refreshStartMs - lastRefreshFinishedAt
        if (lastRefreshFinishedAt > 0L && sinceLastFinish < MIN_REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Refresh requested ${sinceLastFinish}ms after last completion; skipping (floor=${MIN_REFRESH_INTERVAL_MS}ms)")
            refreshing.set(false)
            return
        }
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
            Log.d(
                TAG,
                if (noPubkeyMode) {
                    "Refresh starting: no-pubkey mode, ${extraRelays.size} relay(s)"
                } else {
                    "Refresh starting for $aggPubkey (extraRelays=${extraRelays.size})"
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
                // Refresh the owner's NIP-51 mute list before opening the main subscriptions.
                // The public portion is applied synchronously; the (optional) private portion
                // resolves on its own coroutine via the signer so a slow Amber prompt does not
                // hold up the rest of the refresh.
                runCatching { refreshMuteList(dao, aggPubkey) }
                    .onFailure { Log.e(TAG, "refreshMuteList failed", it) }

                val contactListMs = System.currentTimeMillis()
                val contactList = loadOrBootstrapContactList(dao, aggPubkey)
                val follows = contactList?.verifiedFollowKeySet() ?: emptySet()
                val authors = (follows + aggPubkey).toSet()
                authorCount = authors.size
                Log.d(
                    TAG,
                    "Contact list resolved in ${System.currentTimeMillis() - contactListMs}ms: " +
                        "${follows.size} follows (+ self) for $aggPubkey (createdAt=${contactList?.createdAt ?: "none"})",
                )

                val indexers = indexerRelays()
                val sources = aggregatorSourceRelays()

                // Resolve NIP-65 for all authors in one batch instead of N sequential fetches.
                val uncached = mutableSetOf<String>()
                for (pk in authors) {
                    val cached = dao.getAdvertisedRelayList(pk)?.toEvent() as? AdvertisedRelayListEvent
                    if (cached != null) cachedRelayLists[pk] = cached else uncached.add(pk)
                }
                Log.d(TAG, "NIP-65 cache: ${cachedRelayLists.size} hit, ${uncached.size} miss")

                val fetchMs = System.currentTimeMillis()
                fetched = if (uncached.isNotEmpty()) {
                    Log.d(TAG, "Fetching ${uncached.size} NIP-65 records from ${indexers.size} indexers")
                    batchFetchRelayLists(uncached, indexers, BATCH_FETCH_TIMEOUT_MS)
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

                // Build each author's candidate write-relay list (with the per-author
                // fallback for missing NIP-65), then cap each author at MAX_RELAYS_PER_AUTHOR
                // using a greedy popularity-based picker to converge on a small shared set
                // of relays. Aggregator-source relays are then layered on top with the full
                // author set, so they act as a backstop mirror for every followed pubkey.
                val authorWriteRelays = mutableMapOf<String, List<NormalizedRelayUrl>>()
                var fellBackCount = 0
                for (pk in authors) {
                    val relayListEvent = cachedRelayLists[pk] ?: fetched[pk]
                    authorWriteRelays[pk] = relayListEvent
                        ?.writeRelays()
                        ?.mapNotNull { raw -> normalizeRemote(raw) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            fellBackCount++
                            FALLBACK_OUTBOX_RELAYS
                        }
                }
                val capped = capRelaysPerAuthor(authorWriteRelays, MAX_RELAYS_PER_AUTHOR)
                relayToAuthors.putAll(capped)
                sources.forEach { relay ->
                    relayToAuthors.getOrPut(relay) { mutableSetOf() }.addAll(authors)
                }
                Log.d(
                    TAG,
                    "Outbox map built: ${relayToAuthors.size} relays for ${authors.size} authors " +
                        "(cap=$MAX_RELAYS_PER_AUTHOR/author, ${sources.size} aggregator source(s), " +
                        "$fellBackCount author(s) had no NIP-65, fell back to ${FALLBACK_OUTBOX_RELAYS.size} relays)",
                )

                // Same cap-then-overlay-sources strategy for kind-0 metadata fetches: take
                // each author's NIP-65 write relays (indexers as the fallback for unknown
                // authors), cap per-author, then add the aggregator-source relays.
                val authorMetadataRelays = mutableMapOf<String, List<NormalizedRelayUrl>>()
                var metadataIndexerFallback = 0
                for (pk in authors) {
                    val relayListEvent = cachedRelayLists[pk] ?: fetched[pk]
                    authorMetadataRelays[pk] = relayListEvent
                        ?.writeRelays()
                        ?.mapNotNull { raw -> normalizeRemote(raw) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            metadataIndexerFallback++
                            indexers
                        }
                }
                val metadataRelayToAuthors = capRelaysPerAuthor(authorMetadataRelays, MAX_RELAYS_PER_AUTHOR)
                sources.forEach { relay ->
                    metadataRelayToAuthors.getOrPut(relay) { mutableSetOf() }.addAll(authors)
                }
                val metadataMs = System.currentTimeMillis()
                val metadataFetched = batchFetchMetadata(metadataRelayToAuthors, BATCH_FETCH_TIMEOUT_MS)
                Log.d(
                    TAG,
                    "Metadata batch fetch returned ${metadataFetched.size}/${authors.size} " +
                        "from ${metadataRelayToAuthors.size} relays " +
                        "(cap=$MAX_RELAYS_PER_AUTHOR/author, ${sources.size} aggregator source(s), " +
                        "$metadataIndexerFallback author(s) had no NIP-65, fell back to indexers) " +
                        "in ${System.currentTimeMillis() - metadataMs}ms",
                )
            }

            publishStatus(phase = AggregatorPhase.REFRESHING)

            // Drop onion relays we've previously seen fail with "SOCKS: Connection refused".
            // Either Tor isn't running or the hidden service is gone; retrying every refresh
            // (and Quartz's own reconnect loop) burns battery for nothing. The skip persists
            // until stop() so a restart clears it.
            if (skippedOnionRelays.isNotEmpty()) {
                val before = relayToAuthors.size
                relayToAuthors.keys.removeAll(skippedOnionRelays)
                val removed = before - relayToAuthors.size
                if (removed > 0) {
                    Log.d(TAG, "Excluding $removed skipped onion relay(s) from this refresh")
                }
            }

            // Kinds 0 (metadata) and 3 (contact list) are fetched via dedicated unfiltered
            // paths above (loadOrBootstrapContactList, batchFetchMetadata) — exclude them
            // here so they aren't also subscribed with a `since` ceiling on the main and
            // tagged subs, which would risk masking newer copies behind the cursor.
            val subscriptionKinds = Settings.relayAggregatorKinds.toList() -
                MetadataEvent.KIND - ContactListEvent.KIND
            val bootstrapSince = computeBootstrapSince()
            val allAuthors = relayToAuthors.values.flatten().toSet()
            seedLastEventTimestamps(dao, allAuthors, subscriptionKinds)
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
                val pendingSubscribes = mutableListOf<Pair<String, List<Filter>>>()
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
                            authors = chunk.ifEmpty { null },
                            kinds = subscriptionKinds,
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
                // Publish the new entries to activeRelaySubs BEFORE issuing any REQ for this
                // relay. Otherwise the listener races: an event response arrives between
                // sendSubscribe() and the final swap below, lookup misses, and the event is
                // dropped as "unknown relay (no active subs)".
                newActiveSubs[relay] = entries
                activeRelaySubs[relay] = entries.toMutableList()
                pendingSubscribes.forEach { (subId, filters) -> sendSubscribe(relay, subId, filters) }
                if (existing.size > chunks.size) {
                    for (i in chunks.size until existing.size) {
                        val oldId = existing[i].subId
                        runCatching { Citrine.instance.client.unsubscribe(oldId) }
                        trackedSubIds.remove(oldId)
                        closedExtraChunkSubs++
                    }
                }
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

            if (!noPubkeyMode && Settings.relayAggregatorIncludeTagged) {
                val aggRelayList = cachedRelayLists[aggPubkey] ?: fetched[aggPubkey]
                val readRelays = aggRelayList?.readRelays()
                    ?.mapNotNull { raw -> normalizeRemote(raw) }
                    ?: emptyList()
                val taggedSubRelays = (readRelays + FALLBACK_OUTBOX_RELAYS).toSet() - skippedOnionRelays
                val id = taggedSubId ?: newSubId().also { taggedSubId = it }
                trackedSubIds.add(id)
                val filters = listOf(
                    Filter(
                        tags = mapOf("p" to listOf(aggPubkey)),
                        kinds = subscriptionKinds,
                        since = bootstrapSince,
                    ),
                )
                if (taggedSubRelays.isNotEmpty()) {
                    // Send ONE subscribe with the full relay set; Quartz overwrites the prior
                    // desiredSubs[subId] entry on each call, so iterating would leave only the
                    // last relay actually subscribed.
                    Log.d(
                        TAG,
                        "Tagged sub: $id to ${taggedSubRelays.size} relays " +
                            "(${readRelays.size} from inbox, ${FALLBACK_OUTBOX_RELAYS.size} fallback)",
                    )
                    val taggedEntry = ChunkSubscription(
                        subId = id,
                        authors = emptySet(),
                        since = bootstrapSince,
                    )
                    taggedSubRelays.forEach { relay ->
                        newActiveSubs.getOrPut(relay) { mutableListOf() }.add(taggedEntry)
                        // Publish into live state BEFORE the subscribe call, same race fix as
                        // the main-sub loop above.
                        activeRelaySubs.compute(relay) { _, current ->
                            val list = current?.let { ArrayList(it) } ?: ArrayList()
                            list.add(taggedEntry)
                            list
                        }
                    }
                    runCatching {
                        Citrine.instance.client.subscribe(id, taggedSubRelays.associateWith { filters })
                    }.onFailure { Log.e(TAG, "tagged subscribe failed", it) }
                }
            } else {
                taggedSubId?.let {
                    Log.d(TAG, "Tagged sub disabled, unsubscribing $it")
                    runCatching { Citrine.instance.client.unsubscribe(it) }
                    trackedSubIds.remove(it)
                }
                taggedSubId = null
            }

            // activeRelaySubs has been populated incrementally during the main-sub and
            // tagged-sub passes (so the listener can resolve responses without racing the
            // final swap). Just drop relays that vanished this refresh — their subIds were
            // already unsubscribed in the closedGoneRelaySubs loop above.
            val staleRelays = activeRelaySubs.keys.toSet() - newActiveSubs.keys
            staleRelays.forEach { activeRelaySubs.remove(it) }
            // Replace lists for relays that survived to make sure the in-memory state matches
            // newActiveSubs exactly (e.g., picks up tagged-sub-only relays uniformly).
            newActiveSubs.forEach { (relay, entries) -> activeRelaySubs[relay] = entries }

            val newSubscribedRelays = newActiveSubs.keys
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
            lastRefreshFinishedAt = System.currentTimeMillis()
            refreshing.set(false)
        }
    }

    /**
     * Validate that [ev] matches the filter we asked [relayUrl] for under [subId]. Misbehaving or
     * lax relays sometimes ship events outside of the REQ filter (wrong author, wrong kind, older
     * than `since`, missing the required tag). Returns null when it matches; otherwise a short
     * human-readable reason for the caller to log.
     */
    private fun filterMismatchReason(relayUrl: NormalizedRelayUrl, subId: String, ev: Event): String? {
        val entries = activeRelaySubs[relayUrl] ?: return "unknown relay (no active subs)"
        val entry = entries.firstOrNull { it.subId == subId } ?: return "unknown subId"

        val kinds = Settings.relayAggregatorKinds
        if (kinds.isNotEmpty() && ev.kind !in kinds) {
            return "kind ${ev.kind} not in requested kinds $kinds"
        }

        if (ev.createdAt < entry.since) {
            return "createdAt ${ev.createdAt} < requested since ${entry.since}"
        }

        if (entry.authors.isNotEmpty()) {
            if (ev.pubKey !in entry.authors) {
                return "pubkey ${ev.pubKey} not in requested authors (${entry.authors.size})"
            }
        } else if (subId == taggedSubId) {
            val aggPubkey = Settings.aggregatorPubkey
            if (aggPubkey.isBlank()) return "tagged sub active but aggregator pubkey is blank"
            val tagged = ev.tags.any { it.size > 1 && it[0] == "p" && it[1] == aggPubkey }
            if (!tagged) return "missing required p-tag for $aggPubkey"
        }
        return null
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

    // Marks the counters as needing publication. The periodic emitter started in `start()`
    // observes the flag at most once per STATUS_EMIT_INTERVAL_MS and writes a single
    // snapshot to `_status` if set. Cheap enough to call on the relay listener thread for
    // every received event.
    private fun refreshStatusCounters() {
        statusDirty.set(true)
    }

    // Publishes the current counter snapshot to `_status` if any counter changed since
    // the last emission. Phase changes go through `publishStatus` directly and bypass
    // the throttle so UI/notification reflect transitions promptly.
    private fun flushPendingStatus() {
        if (!statusDirty.compareAndSet(true, false)) return
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

    // Resolves the user-configured NIP-65 indexer relays. An empty user set falls back to
    // the built-in defaults so NIP-65 / contact-list / mute-list bootstrap lookups never
    // silently break when the user clears the list.
    private fun indexerRelays(): List<NormalizedRelayUrl> {
        val raw = Settings.relayAggregatorIndexerRelays
            .takeIf { it.isNotEmpty() }
            ?: Settings.DEFAULT_NIP65_INDEXER_RELAYS
        return raw.mapNotNull { normalizeRemote(it) }
    }

    // Resolves the user-configured aggregator-source relays (e.g. wss://aggr.nostr.land).
    // No default fallback: an empty user list means "no aggregator overlay", which is a
    // legitimate configuration (rely entirely on per-author NIP-65 routing).
    private fun aggregatorSourceRelays(): List<NormalizedRelayUrl> = Settings.relayAggregatorSourceRelays.mapNotNull { normalizeRemote(it) }

    // Picks at most [cap] relays per author from each author's candidate write-relay list,
    // greedily preferring relays that already cover many other authors. The popularity
    // score is computed across all authors once, then each author independently picks its
    // top-[cap] relays by that score with ties broken by candidate-list order. This keeps
    // total open WebSocket count low while still spreading coverage across multiple relays
    // per author.
    private fun capRelaysPerAuthor(
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

    /**
     * Fetches a single replaceable event of [kind] for [pubkey] from [relays] with no `since`
     * filter. Used both for the user's own kind 3 / kind 0 and for opportunistic backfill of
     * strangers whose events arrive on the tagged sub. Returns null on timeout, error, or
     * relay miss.
     */
    private suspend fun fetchSingleReplaceable(
        kind: Int,
        pubkey: String,
        relays: List<NormalizedRelayUrl>,
    ): Event? {
        if (relays.isEmpty()) return null
        val filters = listOf(
            Filter(
                kinds = listOf(kind),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()
        return try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                Citrine.instance.client.fetchFirst(subId, relays.associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetch kind $kind for $pubkey failed", e)
            null
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
        }
    }

    /**
     * For an author we just saw an event from but who has no profile / contact list / NIP-65
     * relay list in the local DB, pull whatever's missing from the indexer relays once and
     * persist it. The kind-10002 list is fetched first so that, if we just learned it, we can
     * route the kind-0 fetch to the user's own write relays instead of the indexers. No-op
     * when everything is already cached. Caller is responsible for deduping repeated
     * invocations via [backfilledPubkeys].
     */
    private suspend fun backfillUserIfMissing(dao: EventDao, pubkey: String) {
        val needsMetadata = dao.getMetadata(pubkey) == null
        val needsContactList = dao.getContactList(pubkey) == null
        val needsRelayList = dao.getAdvertisedRelayList(pubkey) == null
        if (!needsMetadata && !needsContactList && !needsRelayList) return
        Log.d(
            TAG,
            "Backfilling unknown user $pubkey " +
                "(metadata=$needsMetadata, contacts=$needsContactList, relayList=$needsRelayList)",
        )
        val indexers = indexerRelays()
        if (needsRelayList) {
            fetchSingleReplaceable(AdvertisedRelayListEvent.KIND, pubkey, indexers)?.let {
                CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true)
            }
        }
        if (needsMetadata) {
            val metadataRelays = userWriteRelays(dao, pubkey) ?: indexers
            fetchSingleReplaceable(MetadataEvent.KIND, pubkey, metadataRelays)?.let {
                CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true)
            }
        }
        if (needsContactList) {
            fetchSingleReplaceable(ContactListEvent.KIND, pubkey, indexers)?.let {
                CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true)
            }
        }
    }

    /**
     * Returns [pubkey]'s NIP-65 write relays as a normalized list, or null when no relay list
     * is cached or the cached list has no usable write relays. Reads the freshly persisted
     * value from the DB so a kind-10002 fetch performed earlier in the same refresh is visible.
     */
    private suspend fun userWriteRelays(dao: EventDao, pubkey: String): List<NormalizedRelayUrl>? {
        val relayList = dao.getAdvertisedRelayList(pubkey)?.toEvent() as? AdvertisedRelayListEvent
        return relayList
            ?.writeRelays()
            ?.mapNotNull { raw -> normalizeRemote(raw) }
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun loadOrBootstrapContactList(
        dao: EventDao,
        pubkey: String,
    ): ContactListEvent? {
        val cached = dao.getContactList(pubkey)?.toEvent() as? ContactListEvent
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
                Citrine.instance.client.fetchFirst(subId, indexerRelays().associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap contact list failed for $pubkey", e)
            null
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
        }
        fetched?.let { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
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
     * Hot-path mute check used by the consumer coroutine before [innerProcessEvent].
     * - The aggregator owner's own events are never muted (otherwise refreshing the kind-10000
     *   itself could be discarded if the owner ever appears in their own list).
     * - Pubkey check first: O(1) hash lookup, no allocations.
     * - Word check second: only runs when there are muted words AND the event has content.
     *   Lower-cases the content once and tests every muted word against the same buffer.
     */
    private fun shouldDropForMute(ev: Event): Boolean {
        if (ev.pubKey == Settings.aggregatorPubkey) return false
        if (mutedPubkeys.contains(ev.pubKey)) return true
        val words = mutedWords
        if (words.isEmpty() || ev.content.isEmpty()) return false
        val lower = ev.content.lowercase()
        return words.any { lower.contains(it) }
    }

    /**
     * Read whatever kind-10000 the local DB already has for [pubkey] and apply its public
     * portion to [mutedPubkeys] / [mutedWords]. Called from [start] before the first refresh
     * so events admitted in the brief window before the network fetch completes still respect
     * the muted set from a prior session. Does not touch the signer (decryption is reserved
     * for the network refresh path).
     */
    private suspend fun loadCachedMuteListFromDb(dao: EventDao, pubkey: String) {
        val cached = dao.getMuteList(pubkey)?.toEvent() ?: return
        if (cached.createdAt <= muteListSeenAt) return
        val (pubs, words) = extractPublicMuteEntries(cached)
        mutedPubkeys = pubs
        mutedWords = words
        muteListSeenAt = cached.createdAt
        Log.d(TAG, "Mute list (cache only): ${pubs.size} pubkeys, ${words.size} words (createdAt=${cached.createdAt})")
    }

    /**
     * Fetch the freshest kind-10000 for [pubkey] from cache + indexer relays and apply its
     * public portion synchronously. If a signer is configured and the event has encrypted
     * content, kick off an off-main job to decrypt the private portion and merge the result.
     */
    private suspend fun refreshMuteList(dao: EventDao, pubkey: String) {
        if (pubkey.isBlank()) return
        val ev = loadOrBootstrapMuteList(dao, pubkey) ?: run {
            // Owner has no published mute list yet — clear any stale state from a previous pubkey.
            mutedPubkeys = emptySet()
            mutedWords = emptySet()
            muteListSeenAt = 0L
            return
        }
        if (ev.createdAt <= muteListSeenAt && mutedPubkeys.isNotEmpty()) return
        val (publicPubs, publicWords) = extractPublicMuteEntries(ev)
        mutedPubkeys = publicPubs
        mutedWords = publicWords
        muteListSeenAt = ev.createdAt
        Log.d(TAG, "Mute list refreshed (public): ${publicPubs.size} pubkeys, ${publicWords.size} words")
        scheduleDecryptPrivateMute(ev, publicPubs, publicWords)
    }

    /**
     * Trigger an async refresh from the live-event consumer when the owner publishes a new
     * kind-10000 via their other clients. Re-uses [refreshMuteList] so cache+indexer+decrypt
     * paths stay identical.
     */
    private fun scheduleMuteListRefresh(database: AppDatabase) {
        val s = scope ?: return
        muteRefreshJob?.cancel()
        muteRefreshJob = s.launch {
            try {
                refreshMuteList(database.eventDao(), Settings.aggregatorPubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "live mute-list refresh failed", e)
            }
        }
    }

    /**
     * Decrypt the encrypted private mute entries via the configured external signer and merge
     * them into the in-memory sets. No-op when no signer is configured, the content is empty,
     * or decryption fails. Logs at debug on failure — never throws.
     */
    private fun scheduleDecryptPrivateMute(
        ev: Event,
        publicPubs: Set<String>,
        publicWords: Set<String>,
    ) {
        val s = scope ?: return
        if (ev.content.isEmpty()) return
        val signer = relayAuthSigner ?: return
        muteRefreshJob?.cancel()
        muteRefreshJob = s.launch {
            try {
                val plaintext = withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                    // NostrSignerExternal.decrypt routes to NIP-04 or NIP-44 based on the
                    // ciphertext format, so a single call covers both encoding eras of
                    // kind-10000 events.
                    signer.decrypt(ev.content, ev.pubKey)
                } ?: return@launch
                val (privPubs, privWords) = parsePrivateMuteTags(plaintext)
                if (privPubs.isEmpty() && privWords.isEmpty()) return@launch
                mutedPubkeys = publicPubs + privPubs
                mutedWords = publicWords + privWords
                Log.d(
                    TAG,
                    "Mute list private merge: +${privPubs.size} pubkeys, +${privWords.size} words " +
                        "(total ${mutedPubkeys.size} pubkeys, ${mutedWords.size} words)",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Private mute-list decrypt failed: ${e.message}")
            }
        }
    }

    private fun extractPublicMuteEntries(ev: Event): Pair<Set<String>, Set<String>> {
        val pubs = mutableSetOf<String>()
        val words = mutableSetOf<String>()
        for (tag in ev.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "p" -> {
                    val v = tag[1]
                    if (v.isNotBlank()) pubs.add(v)
                }
                "word" -> {
                    val v = tag[1].trim().lowercase()
                    if (v.isNotBlank()) words.add(v)
                }
            }
        }
        return pubs to words
    }

    private fun parsePrivateMuteTags(plaintext: String): Pair<Set<String>, Set<String>> {
        val pubs = mutableSetOf<String>()
        val words = mutableSetOf<String>()
        try {
            val tags: List<List<String>> = JacksonMapper.mapper.readValue(plaintext)
            for (tag in tags) {
                if (tag.size < 2) continue
                when (tag[0]) {
                    "p" -> {
                        val v = tag[1]
                        if (v.isNotBlank()) pubs.add(v)
                    }
                    "word" -> {
                        val v = tag[1].trim().lowercase()
                        if (v.isNotBlank()) words.add(v)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "parsePrivateMuteTags failed: ${e.message}")
        }
        return pubs to words
    }

    private suspend fun loadOrBootstrapMuteList(dao: EventDao, pubkey: String): Event? {
        val cached = dao.getMuteList(pubkey)?.toEvent()
        val filters = listOf(
            Filter(
                kinds = listOf(10000),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()
        val fetched = try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                Citrine.instance.client.fetchFirst(subId, indexerRelays().associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap mute list failed for $pubkey", e)
            null
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
        }
        fetched?.let { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
        return when {
            fetched == null -> cached
            cached == null -> fetched
            fetched.createdAt >= cached.createdAt -> fetched
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
    private suspend fun batchFetchMetadata(
        relayToAuthors: Map<NormalizedRelayUrl, Set<String>>,
        timeoutMs: Long,
    ): Map<String, MetadataEvent> {
        if (relayToAuthors.isEmpty()) return emptyMap()
        if (!Citrine.instance.client.isActive()) Citrine.instance.client.connect()

        val results = ConcurrentHashMap<String, MetadataEvent>()
        val subId = newSubId()
        val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
        val expected = relayToAuthors.keys
        val done = CompletableDeferred<Unit>()

        val collector = object : RelayConnectionListener {
            override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
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

        Citrine.instance.client.addConnectionListener(collector)
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
                Citrine.instance.client.subscribe(subId, perRelayFilters)
            }.onFailure { Log.e(TAG, "batch metadata subscribe failed", it) }
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { Citrine.instance.client.unsubscribe(subId) }
            Citrine.instance.client.removeConnectionListener(collector)
        }

        results.values.forEach {
            runCatching { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
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
                val mismatchReason = filterMismatchReason(relay.url, msg.subId, ev)
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
                if (backfilledPubkeys.add(ev.pubKey)) {
                    backfillChannel?.trySend(ev.pubKey)
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
            if (isOnionConnectionRefused(relay.url, errorMessage)) {
                skipOnionRelay(relay.url, errorMessage)
            }
        }
    }

    // OkHttp surfaces a Tor SOCKS failure as "WebSocket Failure: SOCKS: Connection refused"
    // (with optional address suffixes). Match the substring rather than the full string so
    // we don't miss variants from different OkHttp / SocketException versions.
    private fun isOnionConnectionRefused(url: NormalizedRelayUrl, errorMessage: String): Boolean {
        if (!Citrine.instance.isOnionUrl(url.url)) return false
        return errorMessage.contains("SOCKS", ignoreCase = true) &&
            errorMessage.contains("Connection refused", ignoreCase = true)
    }

    private fun skipOnionRelay(url: NormalizedRelayUrl, errorMessage: String) {
        if (!skippedOnionRelays.add(url)) return
        Log.d(TAG, "Skipping onion relay ${url.url} after connection refused: $errorMessage")
        val entries = activeRelaySubs.remove(url) ?: return
        // The tagged sub uses one subId across many relays; unsubscribing it here would
        // tear it down on every other relay too. The main subs allocate a fresh subId per
        // relay, so those are safe to drop.
        val sharedTaggedId = taggedSubId
        entries.forEach { entry ->
            if (entry.subId == sharedTaggedId) return@forEach
            runCatching { Citrine.instance.client.unsubscribe(entry.subId) }
            trackedSubIds.remove(entry.subId)
        }
        subscribedRelays.remove(url)
        refreshStatusCounters()
    }
}
