package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.server.NsiteInfo
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
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
import com.vitorpamplona.quartz.utils.TimeUtils
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Discovers, downloads, and updates nsites (NIP-5A static websites).
 *
 * Manifests are kind 15128 (root site) or 35128 (named site, requires a `d` tag). Each
 * manifest has `["path", "/url/path", "<sha256>"]` tags mapping URLs to blob hashes and an
 * `["x", "<sha256>", "aggregate"]` tag used to detect content changes. Blobs are downloaded
 * from Blossom servers (manifest `server` hints, else the author's kind-10063 list) and
 * verified against their path hash before being written to filesDir/nsites/<folderName>.
 */
object NsiteManager {
    private const val TAG = "NsiteManager"

    const val KIND_ROOT_SITE = 15128
    const val KIND_NAMED_SITE = 35128
    private const val KIND_SERVER_LIST = 10063
    private val NSITE_KINDS = listOf(KIND_ROOT_SITE, KIND_NAMED_SITE)

    private const val BATCH_FETCH_TIMEOUT_MS = 15_000L
    private const val DISCOVERY_TIMEOUT_MS = 20_000L
    private const val DISCOVERY_LIMIT = 500

    private val FALLBACK_RELAYS = setOf(
        "wss://relay.damus.io/",
        "wss://nos.lol/",
        "wss://relay.primal.net/",
    )

    private val DEFAULT_BLOSSOM_SERVERS = listOf("https://blossom.primal.net")

    private val installing = AtomicBoolean(false)

    sealed interface DiscoveryState {
        data object Idle : DiscoveryState
        data object Loading : DiscoveryState
        data class Loaded(val nsites: List<DiscoveredNsite>) : DiscoveryState
        data class Error(val message: String) : DiscoveryState
    }

    data class DiscoveredNsite(
        val address: String,
        val pubkey: String,
        val kind: Int,
        val dTag: String,
        val displayName: String,
        val aggregateHash: String,
        val serverHints: List<String>,
        val alreadyInstalled: Boolean,
    )

    data class NsiteUpdate(
        val nsite: NsiteInfo,
        val newManifest: Event,
        val newAggregateHash: String,
    )

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    // region relays

    private fun normalizeRemote(raw: String): NormalizedRelayUrl? {
        val n = RelayUrlNormalizer.normalizeOrNull(raw) ?: return null
        if (Citrine.instance.isPrivateIp(n.url)) return null
        if (!Settings.useProxy && Citrine.instance.isOnionUrl(n.url)) return null
        return NormalizedRelayUrl(url = n.url)
    }

    private fun discoveryRelays(): List<NormalizedRelayUrl> {
        val configured = (
            Settings.relayAggregatorSourceRelays +
                Settings.relayAggregatorIndexerRelays +
                Settings.relayAggregatorExtraRelays
            )
        val raw = configured.takeIf { it.isNotEmpty() } ?: FALLBACK_RELAYS
        return raw.mapNotNull { normalizeRemote(it) }.ifEmpty { FALLBACK_RELAYS.mapNotNull { normalizeRemote(it) } }
    }

    // endregion

    // region manifest parsing

    private fun dTagOf(ev: Event): String = ev.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""

    private fun addressOf(ev: Event): String = if (ev.kind == KIND_ROOT_SITE) {
        "$KIND_ROOT_SITE:${ev.pubKey}"
    } else {
        "$KIND_NAMED_SITE:${ev.pubKey}:${dTagOf(ev)}"
    }

    private fun aggregateHashOf(ev: Event): String = ev.tags.firstOrNull { it.size >= 3 && it[0] == "x" && it[2] == "aggregate" }?.get(1) ?: ""

    /** Returns (urlPath, sha256) for every `path` tag. */
    private fun pathTagsOf(ev: Event): List<Pair<String, String>> = ev.tags.filter { it.size >= 3 && it[0] == "path" }.map { it[1] to it[2] }

    private fun serverHintsOf(ev: Event): List<String> = ev.tags.filter { it.size >= 2 && it[0] == "server" }.map { it[1] }

    private fun displayNameOf(metadata: Event?, fallback: String): String {
        if (metadata == null) return fallback
        return try {
            val node = JacksonMapper.mapper.readTree(metadata.content)
            val name = node.get("display_name")?.asText()?.takeIf { it.isNotBlank() }
                ?: node.get("name")?.asText()?.takeIf { it.isNotBlank() }
            name ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /** "nsite" + first 16 hex chars of sha256(address): a deterministic, hostname-safe slug. */
    private fun folderNameFor(address: String): String = "nsite" + sha256Hex(address.toByteArray()).take(16)

    private fun shortPubkey(pubkey: String): String = if (pubkey.length > 12) "${pubkey.take(8)}…${pubkey.takeLast(4)}" else pubkey

    // endregion

    // region discovery

    suspend fun discover(): List<DiscoveredNsite> {
        _discoveryState.value = DiscoveryState.Loading
        return try {
            val relays = discoveryRelays()
            if (relays.isEmpty()) {
                _discoveryState.value = DiscoveryState.Loaded(emptyList())
                return emptyList()
            }
            val manifests = collectManifests(relays)
            // Dedupe by address keeping the newest manifest.
            val byAddress = HashMap<String, Event>()
            for (ev in manifests) {
                val addr = addressOf(ev)
                val prev = byAddress[addr]
                if (prev == null || prev.createdAt < ev.createdAt) byAddress[addr] = ev
            }

            val names = fetchDisplayNames(byAddress.values.map { it.pubKey }.toSet(), relays)
            val installedAddresses = Settings.nsites.map { it.address }.toSet()

            val result = byAddress.values.map { ev ->
                val addr = addressOf(ev)
                val dTag = if (ev.kind == KIND_NAMED_SITE) dTagOf(ev) else ""
                val fallback = dTag.takeIf { it.isNotBlank() } ?: shortPubkey(ev.pubKey)
                DiscoveredNsite(
                    address = addr,
                    pubkey = ev.pubKey,
                    kind = ev.kind,
                    dTag = dTag,
                    displayName = displayNameOf(names[ev.pubKey], fallback),
                    aggregateHash = aggregateHashOf(ev),
                    serverHints = serverHintsOf(ev),
                    alreadyInstalled = installedAddresses.contains(addr),
                )
            }.sortedBy { it.displayName.lowercase() }

            _discoveryState.value = DiscoveryState.Loaded(result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "nsite discovery failed", e)
            _discoveryState.value = DiscoveryState.Error(e.message ?: "Discovery failed")
            emptyList()
        }
    }

    private suspend fun collectManifests(relays: List<NormalizedRelayUrl>): List<Event> {
        val client = Citrine.instance.client
        if (!client.isActive()) client.connect()

        val results = ConcurrentHashMap<String, Event>()
        val subId = newSubId()
        val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
        val expected = relays.toSet()
        val done = CompletableDeferred<Unit>()

        val collector = object : RelayConnectionListener {
            override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
                when (msg) {
                    is EventMessage -> {
                        if (msg.subId != subId) return
                        val ev = msg.event
                        if (ev.kind == KIND_ROOT_SITE || ev.kind == KIND_NAMED_SITE) {
                            results[ev.id] = ev
                        }
                    }
                    is EoseMessage -> {
                        if (msg.subId != subId) return
                        markDone(relay)
                    }
                    else -> {}
                }
            }

            override fun onDisconnected(relay: IRelayClient) = markDone(relay)

            override fun onCannotConnect(relay: IRelayClient, errorMessage: String) = markDone(relay)

            private fun markDone(relay: IRelayClient) {
                if (expected.contains(relay.url)) {
                    eoseSeen.add(relay.url)
                    if (eoseSeen.size >= expected.size) done.complete(Unit)
                }
            }
        }

        client.addConnectionListener(collector)
        try {
            val filters = listOf(Filter(kinds = NSITE_KINDS, limit = DISCOVERY_LIMIT))
            runCatching { client.subscribe(subId, relays.associateWith { filters }) }
                .onFailure { Log.e(TAG, "nsite discovery subscribe failed", it) }
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { done.await() }
        } finally {
            runCatching { client.unsubscribe(subId) }
            client.removeConnectionListener(collector)
        }
        return results.values.toList()
    }

    private suspend fun fetchDisplayNames(
        pubkeys: Set<String>,
        relays: List<NormalizedRelayUrl>,
    ): Map<String, Event> {
        if (pubkeys.isEmpty()) return emptyMap()
        val client = Citrine.instance.client
        if (!client.isActive()) client.connect()

        val results = ConcurrentHashMap<String, Event>()
        val subId = newSubId()
        val eoseSeen: MutableSet<NormalizedRelayUrl> = ConcurrentHashMap.newKeySet()
        val expected = relays.toSet()
        val done = CompletableDeferred<Unit>()

        val collector = object : RelayConnectionListener {
            override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
                when (msg) {
                    is EventMessage -> {
                        if (msg.subId != subId) return
                        val ev = msg.event
                        if (ev.kind != 0) return
                        val prev = results[ev.pubKey]
                        if (prev == null || prev.createdAt < ev.createdAt) results[ev.pubKey] = ev
                    }
                    is EoseMessage -> {
                        if (msg.subId != subId) return
                        markDone(relay)
                    }
                    else -> {}
                }
            }

            override fun onDisconnected(relay: IRelayClient) = markDone(relay)

            override fun onCannotConnect(relay: IRelayClient, errorMessage: String) = markDone(relay)

            private fun markDone(relay: IRelayClient) {
                if (expected.contains(relay.url)) {
                    eoseSeen.add(relay.url)
                    if (eoseSeen.size >= expected.size) done.complete(Unit)
                }
            }
        }

        client.addConnectionListener(collector)
        try {
            val filters = listOf(Filter(kinds = listOf(0), authors = pubkeys.toList()))
            runCatching { client.subscribe(subId, relays.associateWith { filters }) }
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) { done.await() }
        } finally {
            runCatching { client.unsubscribe(subId) }
            client.removeConnectionListener(collector)
        }
        return results
    }

    // endregion

    // region single fetches

    private suspend fun fetchManifest(
        pubkey: String,
        kind: Int,
        dTag: String,
        relays: List<NormalizedRelayUrl>,
    ): Event? {
        if (relays.isEmpty()) return null
        val client = Citrine.instance.client
        val filters = listOf(
            Filter(
                kinds = listOf(kind),
                authors = listOf(pubkey),
                tags = if (dTag.isNotEmpty()) mapOf("d" to listOf(dTag)) else null,
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!client.isActive()) client.connect()
        return try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                client.fetchFirst(subId, relays.associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetch manifest $kind:$pubkey failed", e)
            null
        } finally {
            runCatching { client.unsubscribe(subId) }
        }
    }

    private suspend fun resolveServers(pubkey: String, manifest: Event, relays: List<NormalizedRelayUrl>): List<String> {
        val fromManifest = serverHintsOf(manifest).mapNotNull { normalizeServerUrl(it) }
        if (fromManifest.isNotEmpty()) return fromManifest.distinct()

        val serverList = fetchManifest(pubkey, KIND_SERVER_LIST, "", relays)
        val fromList = serverList?.let { serverHintsOf(it).mapNotNull { url -> normalizeServerUrl(url) } } ?: emptyList()
        return fromList.distinct().ifEmpty { DEFAULT_BLOSSOM_SERVERS }
    }

    private fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("https://")) return null
        return trimmed
    }

    // endregion

    // region install / update

    /** Installs an nsite discovered via [discover]. Returns the stored [NsiteInfo] or a failure. */
    suspend fun install(discovered: DiscoveredNsite): Result<NsiteInfo> = withContext(Dispatchers.IO) {
        if (Citrine.isImportingEvents) return@withContext Result.failure(IllegalStateException("Busy importing events"))
        if (!installing.compareAndSet(false, true)) return@withContext Result.failure(IllegalStateException("Another nsite operation is running"))
        try {
            val relays = discoveryRelays()
            val manifest = fetchManifest(discovered.pubkey, discovered.kind, discovered.dTag, relays)
                ?: return@withContext Result.failure(IllegalStateException("Could not fetch manifest"))
            downloadAndStore(discovered.address, discovered.pubkey, discovered.kind, discovered.dTag, discovered.displayName, manifest, relays)
        } finally {
            installing.set(false)
        }
    }

    /** Re-downloads an nsite whose manifest changed. */
    suspend fun applyUpdate(update: NsiteUpdate): Result<NsiteInfo> = withContext(Dispatchers.IO) {
        if (Citrine.isImportingEvents) return@withContext Result.failure(IllegalStateException("Busy importing events"))
        if (!installing.compareAndSet(false, true)) return@withContext Result.failure(IllegalStateException("Another nsite operation is running"))
        try {
            val nsite = update.nsite
            val relays = discoveryRelays()
            downloadAndStore(nsite.address, nsite.pubkey, nsite.kind, nsite.dTag, nsite.displayName, update.newManifest, relays, nsite.autoUpdate)
        } finally {
            installing.set(false)
        }
    }

    suspend fun applyUpdateByAddress(address: String): Result<NsiteInfo> {
        val nsite = Settings.nsites.firstOrNull { it.address == address }
            ?: return Result.failure(IllegalStateException("nsite not installed"))
        val relays = discoveryRelays()
        val manifest = fetchManifest(nsite.pubkey, nsite.kind, nsite.dTag, relays)
            ?: return Result.failure(IllegalStateException("Could not fetch manifest"))
        return applyUpdate(NsiteUpdate(nsite, manifest, aggregateHashOf(manifest)))
    }

    private suspend fun downloadAndStore(
        address: String,
        pubkey: String,
        kind: Int,
        dTag: String,
        displayName: String,
        manifest: Event,
        relays: List<NormalizedRelayUrl>,
        autoUpdate: Boolean = false,
    ): Result<NsiteInfo> {
        val paths = pathTagsOf(manifest)
        if (paths.isEmpty()) return Result.failure(IllegalStateException("Manifest has no files"))

        val servers = resolveServers(pubkey, manifest, relays)
        if (servers.isEmpty()) return Result.failure(IllegalStateException("No blossom servers found"))

        val folderName = folderNameFor(address)
        val rootDir = File(Citrine.instance.filesDir, "nsites/$folderName")
        val tmpDir = File(Citrine.instance.filesDir, "nsites/$folderName.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        try {
            val tmpCanonical = tmpDir.canonicalFile
            for ((urlPath, sha256) in paths) {
                val outFile = File(tmpCanonical, urlPath.trimStart('/')).canonicalFile
                if (outFile.path != tmpCanonical.path && !outFile.path.startsWith(tmpCanonical.path + File.separator)) {
                    return Result.failure(IllegalStateException("Illegal path in manifest: $urlPath"))
                }
                val bytes = downloadBlob(servers, sha256)
                    ?: return Result.failure(IllegalStateException("Failed to download $urlPath"))
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
            }
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            if (e is CancellationException) throw e
            return Result.failure(e)
        }

        // Swap tmp into place.
        rootDir.deleteRecursively()
        if (!tmpDir.renameTo(rootDir)) {
            tmpDir.deleteRecursively()
            return Result.failure(IllegalStateException("Failed to finalize nsite folder"))
        }

        val info = NsiteInfo(
            address = address,
            pubkey = pubkey,
            kind = kind,
            dTag = dTag,
            displayName = displayName,
            folderName = folderName,
            aggregateHash = aggregateHashOf(manifest),
            autoUpdate = autoUpdate,
            lastChecked = TimeUtils.now(),
        )
        upsert(info)
        CustomWebSocketService.server?.startNsiteServer(info)
        return Result.success(info)
    }

    /** Downloads a blob, trying each server in order and verifying its sha256. */
    private fun downloadBlob(servers: List<String>, sha256: String): ByteArray? {
        val client = HttpClientManager.getHttpClient(Settings.proxyAllUrls)
        for (server in servers) {
            try {
                val request = Request.Builder().url("$server/$sha256").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bytes = response.body.bytes()
                    if (sha256Hex(bytes).equals(sha256, ignoreCase = true)) {
                        return bytes
                    } else {
                        Log.w(TAG, "sha256 mismatch for $sha256 from $server")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "blob download failed from $server: ${e.message}")
            }
        }
        return null
    }

    // endregion

    // region update check

    /** Re-fetches every installed manifest and returns the ones whose aggregate hash changed. */
    suspend fun checkForUpdates(): List<NsiteUpdate> {
        val relays = discoveryRelays()
        val updates = mutableListOf<NsiteUpdate>()
        val current = Settings.nsites.toList()
        for (nsite in current) {
            val manifest = fetchManifest(nsite.pubkey, nsite.kind, nsite.dTag, relays) ?: continue
            val newHash = aggregateHashOf(manifest)
            // Record the check time regardless of whether content changed.
            updateLastChecked(nsite.address, TimeUtils.now())
            if (newHash.isNotBlank() && newHash != nsite.aggregateHash) {
                updates.add(NsiteUpdate(nsite, manifest, newHash))
            }
        }
        return updates
    }

    fun setAutoUpdate(address: String, enabled: Boolean) {
        val idx = Settings.nsites.indexOfFirst { it.address == address }
        if (idx < 0) return
        Settings.nsites[idx] = Settings.nsites[idx].copy(autoUpdate = enabled)
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
    }

    fun delete(address: String) {
        val nsite = Settings.nsites.firstOrNull { it.address == address } ?: return
        Settings.nsites.removeAll { it.address == address }
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
        File(Citrine.instance.filesDir, "nsites/${nsite.folderName}").deleteRecursively()
        CustomWebSocketService.server?.stopNsiteServer(nsite.folderName)
    }

    private fun upsert(info: NsiteInfo) {
        val idx = Settings.nsites.indexOfFirst { it.address == info.address }
        if (idx >= 0) Settings.nsites[idx] = info else Settings.nsites.add(info)
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
    }

    private fun updateLastChecked(address: String, time: Long) {
        val idx = Settings.nsites.indexOfFirst { it.address == address }
        if (idx < 0) return
        Settings.nsites[idx] = Settings.nsites[idx].copy(lastChecked = time)
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
    }

    // endregion

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
