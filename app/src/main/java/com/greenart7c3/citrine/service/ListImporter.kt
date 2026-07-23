package com.greenart7c3.citrine.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches a user's Nostr lists (NIP-02 follow list, NIP-51 mute list, and NIP-51 parameterized
 * curation lists) plus the profile metadata for everyone in them, so the access-control screen
 * can offer a picker. The public portions are read from the events' `p` tags; the private
 * (encrypted) portions of the mute and curation lists are decrypted through the Amber-backed
 * [NostrSignerExternal].
 */
object ListImporter {
    private const val TAG = "ListImporter"
    private const val TIMEOUT_MS = 15_000L
    private const val PROFILE_CHUNK = 150

    private val INDEXER_RELAYS = listOf(
        RelayUrlNormalizer.normalize("wss://purplepag.es/"),
        RelayUrlNormalizer.normalize("wss://user.kindpag.es/"),
        RelayUrlNormalizer.normalize("wss://profiles.nostr1.com/"),
        RelayUrlNormalizer.normalize("wss://directory.yabu.me/"),
    )

    data class UserListEntry(val id: String, val title: String, val pubkeys: List<String>)

    data class ProfileInfo(val name: String?, val picture: String?)

    data class Result(val lists: List<UserListEntry>, val profiles: Map<String, ProfileInfo>)

    suspend fun fetch(signer: NostrSignerExternal): Result {
        val client = Citrine.instance.client
        if (!client.isActive()) client.connect()
        val pubKey = signer.pubKey

        val contactList = fetchFirstEvent(listOf(ContactListEvent.KIND), pubKey) as? ContactListEvent
        val muteList = fetchFirstEvent(listOf(10000), pubKey)
        val curations = runCatching {
            client.fetchAll(
                newSubId(),
                INDEXER_RELAYS.associateWith { listOf(Filter(kinds = listOf(30000), authors = listOf(pubKey))) },
                TIMEOUT_MS,
            )
        }.onFailure { Log.e(TAG, "fetch curations failed", it) }.getOrDefault(emptyList())

        val lists = mutableListOf<UserListEntry>()

        contactList?.verifiedFollowKeySet()?.takeIf { it.isNotEmpty() }?.let {
            lists.add(UserListEntry("follow", "Follow list", it.toList()))
        }

        if (muteList != null) {
            val publicPubs = pubkeysFromTags(muteList)
            if (publicPubs.isNotEmpty()) lists.add(UserListEntry("mute_public", "Mute list (public)", publicPubs))
            if (muteList.content.isNotBlank()) {
                val priv = decryptPubkeys(signer, muteList.content, pubKey)
                if (priv.isNotEmpty()) lists.add(UserListEntry("mute_private", "Mute list (private)", priv))
            }
        }

        for (ev in curations) {
            val dTag = ev.tags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) ?: continue
            val publicPubs = pubkeysFromTags(ev)
            val privPubs = if (ev.content.isNotBlank()) decryptPubkeys(signer, ev.content, pubKey) else emptyList()
            val all = (publicPubs + privPubs).distinct()
            if (all.isNotEmpty()) {
                val name = ev.tags
                    .firstOrNull { it.size > 1 && (it[0] == "title" || it[0] == "name") }
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?: dTag
                lists.add(UserListEntry("curation_$dTag", "Curation: $name", all))
            }
        }

        val allPubs = lists.flatMap { it.pubkeys }.toSet()
        val profiles = fetchProfiles(allPubs)
        return Result(lists, profiles)
    }

    private suspend fun fetchFirstEvent(kinds: List<Int>, pubKey: String): Event? {
        val client = Citrine.instance.client
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                client.fetchFirst(
                    newSubId(),
                    INDEXER_RELAYS.associateWith { listOf(Filter(kinds = kinds, authors = listOf(pubKey), limit = 1)) },
                    TIMEOUT_MS,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchFirst $kinds failed for $pubKey", e)
            null
        }
    }

    private fun pubkeysFromTags(ev: Event): List<String> = ev.tags.mapNotNull { tag ->
        tag.takeIf { it.size > 1 && it[0] == "p" }
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
    }.distinct()

    private suspend fun decryptPubkeys(signer: NostrSignerExternal, content: String, author: String): List<String> {
        val plaintext = try {
            withTimeoutOrNull(TIMEOUT_MS) { signer.decrypt(content, author) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "decrypt failed: ${e.message}")
            return emptyList()
        } ?: return emptyList()
        return parsePubkeysFromPlaintext(plaintext)
    }

    private fun parsePubkeysFromPlaintext(plaintext: String): List<String> = try {
        val tags: List<List<String>> = JacksonMapper.mapper.readValue(plaintext)
        tags.mapNotNull { it.takeIf { t -> t.size > 1 && t[0] == "p" }?.get(1)?.takeIf { v -> v.isNotBlank() } }.distinct()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.d(TAG, "parsePubkeysFromPlaintext failed: ${e.message}")
        emptyList()
    }

    private suspend fun fetchProfiles(pubkeys: Set<String>): Map<String, ProfileInfo> {
        if (pubkeys.isEmpty()) return emptyMap()
        val client = Citrine.instance.client
        val results = mutableMapOf<String, Pair<Long, ProfileInfo>>()
        coroutineScope {
            pubkeys.toList().chunked(PROFILE_CHUNK).map { chunk ->
                async(Dispatchers.IO) {
                    val events = runCatching {
                        client.fetchAll(
                            newSubId(),
                            INDEXER_RELAYS.associateWith { listOf(Filter(kinds = listOf(MetadataEvent.KIND), authors = chunk)) },
                            TIMEOUT_MS,
                        )
                    }.onFailure { Log.e(TAG, "fetchProfiles chunk failed", it) }.getOrDefault(emptyList())
                    events.forEach { ev ->
                        val meta = (ev as? MetadataEvent)?.contactMetaData() ?: return@forEach
                        val info = ProfileInfo(meta.bestName(), meta.profilePicture())
                        synchronized(results) {
                            val prev = results[ev.pubKey]
                            if (prev == null || prev.first < ev.createdAt) results[ev.pubKey] = ev.createdAt to info
                        }
                    }
                }
            }.awaitAll()
        }
        return results.mapValues { it.value.second }
    }
}
