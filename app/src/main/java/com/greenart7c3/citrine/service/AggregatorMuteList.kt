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
    @Volatile private var mutedPubkeys: Set<String> = emptySet()

    @Volatile private var lowercaseMutedWords: Set<String> = emptySet()

    @Volatile var muteListSeenAt: Long = 0L
        private set

    @Volatile private var muteRefreshJob: Job? = null

    fun reset() {
        muteRefreshJob?.cancel()
        muteRefreshJob = null
        forgetMuteEntries()
    }

    private fun forgetMuteEntries() {
        mutedPubkeys = emptySet()
        lowercaseMutedWords = emptySet()
        muteListSeenAt = 0L
    }

    fun shouldDropForMute(ev: Event): Boolean {
        if (ev.pubKey == Settings.aggregatorPubkey) return false
        if (mutedPubkeys.contains(ev.pubKey)) return true
        val words = lowercaseMutedWords
        if (words.isEmpty() || ev.content.isEmpty()) return false
        val lower = ev.content.lowercase()
        return words.any { lower.contains(it) }
    }

    suspend fun loadCachedMuteListFromDb(dao: EventDao, pubkey: String) {
        val cached = fetcher.loadCleanLatestByKind(dao, pubkey, 10000)?.toEvent() ?: return
        if (cached.createdAt <= muteListSeenAt) return
        val (pubs, words) = extractPublicMuteEntries(cached)
        mutedPubkeys = pubs
        lowercaseMutedWords = words
        muteListSeenAt = cached.createdAt
        Log.d(TAG, "Mute list (cache only): ${pubs.size} pubkeys, ${words.size} words (createdAt=${cached.createdAt})")
    }

    suspend fun refreshMuteList(dao: EventDao, pubkey: String, skipNetworkIfCached: Boolean = false) {
        if (pubkey.isBlank()) return
        if (skipNetworkIfCached && dao.getLatestEventIdByKind(pubkey, 10000) != null) {
            Log.d(TAG, "Mute list: reusing cached copy for $pubkey")
            return
        }
        val ev = fetcher.loadOrBootstrapMuteList(dao, pubkey) ?: run {
            forgetMuteEntries()
            return
        }
        if (ev.createdAt <= muteListSeenAt && mutedPubkeys.isNotEmpty()) return
        val (publicPubs, publicWords) = extractPublicMuteEntries(ev)
        mutedPubkeys = publicPubs
        lowercaseMutedWords = publicWords
        muteListSeenAt = ev.createdAt
        Log.d(TAG, "Mute list refreshed (public): ${publicPubs.size} pubkeys, ${publicWords.size} words")
        scheduleDecryptPrivateMute(ev, publicPubs, publicWords)
    }

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

    @Suppress("NoComments")
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
                lowercaseMutedWords = publicWords + privWords
                Log.d(
                    TAG,
                    "Mute list private merge: +${privPubs.size} pubkeys, +${privWords.size} words " +
                        "(total ${mutedPubkeys.size} pubkeys, ${lowercaseMutedWords.size} words)",
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
