package com.greenart7c3.citrine.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.json.JacksonMapper
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RelayAggregator"

internal class AggregatorMuteList(
    private val fetcher: AggregatorFetcher,
    private val scopeProvider: () -> CoroutineScope?,
    private val signerProvider: () -> NostrSignerExternal?,
) {
    // NIP-51 (kind 10000) mute list — author pubkeys to drop and content words to drop.
    // Words are pre-lowercased so the hot-path check can match against a single lowercase
    // copy of the event content without per-word allocation. Both sets are swapped as a
    // pair so a partial load never produces an inconsistent view.
    @Volatile private var mutedPubkeys: Set<String> = emptySet()

    @Volatile private var mutedWords: Set<String> = emptySet()

    // `createdAt` of the last kind-10000 we parsed. Lets the live-event refresh path
    // (when the aggregator's own kind-10000 arrives via the regular subscription) skip
    // a redundant decryption round-trip when the event is older than what we already have.
    @Volatile var muteListSeenAt: Long = 0L
        private set

    // Off-main job running the optional NIP-04/44 decryption of the private mute entries.
    // Held so stop() / pubkey-change can cancel an in-flight decrypt before the signer
    // resumes against a stale identity.
    @Volatile private var muteRefreshJob: Job? = null

    fun reset() {
        muteRefreshJob?.cancel()
        muteRefreshJob = null
        mutedPubkeys = emptySet()
        mutedWords = emptySet()
        muteListSeenAt = 0L
    }

    /**
     * Hot-path mute check used by the consumer coroutine before [innerProcessEvent].
     * - The aggregator owner's own events are never muted (otherwise refreshing the kind-10000
     *   itself could be discarded if the owner ever appears in their own list).
     * - Pubkey check first: O(1) hash lookup, no allocations.
     * - Word check second: only runs when there are muted words AND the event has content.
     *   Lower-cases the content once and tests every muted word against the same buffer.
     */
    fun shouldDropForMute(ev: Event): Boolean {
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
    suspend fun loadCachedMuteListFromDb(dao: EventDao, pubkey: String) {
        val cached = fetcher.loadCleanLatestByKind(dao, pubkey, 10000)?.toEvent() ?: return
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
    suspend fun refreshMuteList(dao: EventDao, pubkey: String, skipNetworkIfCached: Boolean = false) {
        if (pubkey.isBlank()) return
        // When the cache is fresh and a mute list is already stored, keep the in-memory sets
        // (warm-loaded on start() or applied by a prior refresh) and skip the network fetch.
        if (skipNetworkIfCached && dao.getLatestEventIdByKind(pubkey, 10000) != null) {
            Log.d(TAG, "Mute list: reusing cached copy for $pubkey")
            return
        }
        val ev = fetcher.loadOrBootstrapMuteList(dao, pubkey) ?: run {
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
    fun scheduleMuteListRefresh(database: AppDatabase) {
        val s = scopeProvider() ?: return
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
        val s = scopeProvider() ?: return
        if (ev.content.isEmpty()) return
        val signer = signerProvider() ?: return
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
}
