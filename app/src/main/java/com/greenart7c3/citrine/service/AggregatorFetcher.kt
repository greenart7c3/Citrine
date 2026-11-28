package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.TagDeduplicator
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RelayAggregator"

internal class AggregatorFetcher(private val relayClient: RelayClient) {
    fun normalizeRemote(raw: String): NormalizedRelayUrl? {
        val n = RelayUrlNormalizer.normalizeOrNull(raw) ?: return null
        if (relayClient.isPrivateIp(n.url)) return null
        if (!Settings.useProxy && relayClient.isOnionUrl(n.url)) return null
        return NormalizedRelayUrl(url = n.url)
    }

    fun indexerRelaysOrDefaults(): List<NormalizedRelayUrl> {
        val raw = Settings.relayAggregatorIndexerRelays
            .takeIf { it.isNotEmpty() }
            ?: Settings.DEFAULT_NIP65_INDEXER_RELAYS
        return raw.mapNotNull { normalizeRemote(it) }
    }

    fun aggregatorSourceRelays(): List<NormalizedRelayUrl> = Settings.relayAggregatorSourceRelays.mapNotNull { normalizeRemote(it) }

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
        if (!relayClient.client.isActive()) relayClient.client.connect()
        return try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                relayClient.client.fetchFirst(subId, relays.associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetch kind $kind for $pubkey failed", e)
            null
        } finally {
            runCatching { relayClient.client.unsubscribe(subId) }
        }
    }

    suspend fun loadCleanLatestByKind(dao: EventDao, pubkey: String, kind: Int): EventWithTags? {
        val id = dao.getLatestEventIdByKind(pubkey, kind) ?: return null
        if (!Settings.duplicateTagsCleaned) {
            TagDeduplicator.deduplicateEvent(dao, id)
        }
        return dao.getById(id)
    }

    suspend fun backfillUserIfMissing(dao: EventDao, pubkey: String, followedAuthors: () -> Set<String>) {
        val needsMetadata = dao.getLatestEventIdByKind(pubkey, MetadataEvent.KIND) == null
        val needsContactList = ContactListPolicy.shouldBackfillContactList(pubkey, followedAuthors()) &&
            dao.getLatestEventIdByKind(pubkey, ContactListEvent.KIND) == null
        val needsRelayList = dao.getLatestEventIdByKind(pubkey, AdvertisedRelayListEvent.KIND) == null
        if (!needsMetadata && !needsContactList && !needsRelayList) return
        Log.d(
            TAG,
            "Backfilling unknown user $pubkey " +
                "(metadata=$needsMetadata, contacts=$needsContactList, relayList=$needsRelayList)",
        )
        val indexers = indexerRelaysOrDefaults()
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

    private suspend fun userWriteRelays(dao: EventDao, pubkey: String): List<NormalizedRelayUrl>? {
        val relayList = loadCleanLatestByKind(dao, pubkey, AdvertisedRelayListEvent.KIND)?.toEvent() as? AdvertisedRelayListEvent
        return relayList
            ?.writeRelays()
            ?.mapNotNull { raw -> normalizeRemote(raw) }
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun loadOrBootstrapContactList(
        dao: EventDao,
        pubkey: String,
        skipNetworkIfCached: Boolean = false,
    ): ContactListEvent? {
        val cached = loadCleanLatestByKind(dao, pubkey, ContactListEvent.KIND)?.toEvent() as? ContactListEvent
        if (skipNetworkIfCached && cached != null) {
            Log.d(TAG, "Contact list: using cached copy for $pubkey (createdAt=${cached.createdAt})")
            return cached
        }
        val filters = listOf(
            Filter(
                kinds = listOf(ContactListEvent.KIND),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!relayClient.client.isActive()) relayClient.client.connect()
        val fetched = try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                relayClient.client.fetchFirst(subId, indexerRelaysOrDefaults().associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap contact list failed for $pubkey", e)
            null
        } finally {
            runCatching { relayClient.client.unsubscribe(subId) }
        }
        fetched?.let { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
        return newerOf(cached, fetched as? ContactListEvent)
    }

    suspend fun loadOrBootstrapMuteList(dao: EventDao, pubkey: String): Event? {
        val cached = loadCleanLatestByKind(dao, pubkey, 10000)?.toEvent()
        val filters = listOf(
            Filter(
                kinds = listOf(10000),
                authors = listOf(pubkey),
                limit = 1,
            ),
        )
        val subId = newSubId()
        if (!relayClient.client.isActive()) relayClient.client.connect()
        val fetched = try {
            withTimeoutOrNull(BATCH_FETCH_TIMEOUT_MS) {
                relayClient.client.fetchFirst(subId, indexerRelaysOrDefaults().associateWith { filters })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap mute list failed for $pubkey", e)
            null
        } finally {
            runCatching { relayClient.client.unsubscribe(subId) }
        }
        fetched?.let { CustomWebSocketService.server?.innerProcessEvent(it, null, fromAggregator = true) }
        return newerOf(cached, fetched)
    }

    private fun <T : Event> newerOf(cached: T?, fetched: T?): T? = when {
        fetched == null -> cached
        cached == null -> fetched
        fetched.createdAt >= cached.createdAt -> fetched
        else -> cached
    }
}
