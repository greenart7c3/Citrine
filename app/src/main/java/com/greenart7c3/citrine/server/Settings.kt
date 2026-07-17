package com.greenart7c3.citrine.server

import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper

object Settings {
    // Event kinds that are signed artifacts never meant to be published to a relay as
    // standalone events. They are rejected at ingestion with an OK false "blocked:" response
    // and never stored:
    //   - 13    NIP-59 seal (inner layer, only valid wrapped in a gift wrap)
    //   - 9734  NIP-57 zap request (sent to the LNURL callback, not relays)
    //   - 22242 NIP-42 client auth (carried only in ["AUTH", ...] frames)
    //   - 24242 Blossom (NIP-B7) blob auth (HTTP Authorization header artifact)
    //   - 27235 NIP-98 HTTP auth (HTTP Authorization header artifact)
    val DEFAULT_REJECTED_KINDS = setOf(13, 9734, 22242, 24242, 27235)

    val DEFAULT_AGGREGATOR_SOURCE_RELAYS = setOf(
        "wss://aggr.nostr.land/",
    )

    val DEFAULT_NIP65_INDEXER_RELAYS = setOf(
        "wss://purplepag.es/",
        "wss://user.kindpag.es/",
        "wss://profiles.nostr1.com/",
        "wss://directory.yabu.me/",
    )

    // Suggested relays for discovering nsites (NIP-5A). Used by the "reset to default" action
    // in the nsite relay editor; an empty user list falls back to the aggregator relays.
    val DEFAULT_NSITE_RELAYS = setOf(
        "wss://nsite.run/",
        "wss://nos.lol/",
        "wss://nostr.land/",
    )

    var allowedKinds: Set<Int> = emptySet()

    // Signed artifacts that must never be stored as standalone events (see DEFAULT_REJECTED_KINDS).
    // Events whose kind is in this set are rejected at ingestion before any storage.
    var rejectedKinds: Set<Int> = DEFAULT_REJECTED_KINDS
    var allowedPubKeys: Set<String> = emptySet()
    var allowedTaggedPubKeys: Set<String> = emptySet()
    var deleteEventsOlderThan: OlderThan = OlderThan.NEVER
    var useSSL: Boolean = false
    var host: String = "127.0.0.1"
    var port: Int = 4869
    var neverDeleteFrom: Set<String> = emptySet()
    var preservedKindsFromDeletion: Set<Int> = setOf(0, 3, 10000, 10002)
    var name: String = "Citrine"
    var ownerPubkey: String = ""
    var contact: String = ""
    var description: String = "A Nostr relay in your phone"
    var relayIcon: String = "https://github.com/greenart7c3/Citrine/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
    var autoBackup = false
    var autoBackupFolder = ""

    // Amber (NIP-55) signer package that holds the relay owner's key. Set together with
    // [ownerPubkey] by the "login with Amber" flow in relay info settings — the owner
    // pubkey can only be configured through that login, never typed manually.
    var relaySignerPackageName = ""

    // NIP-29 relay-based groups are active whenever a relay owner is configured. The
    // owner's key (via the Amber signer) is the relay identity that signs group
    // metadata. With no owner, h-tagged and 39xxx events flow unmanaged so Citrine
    // keeps acting as a plain cache/backup relay; foreign group ids always pass through.
    val nip29Enabled: Boolean
        get() = ownerPubkey.isNotBlank() && relaySignerPackageName.isNotBlank()

    // When true, ephemeral events that no open subscription was listening to receive a
    // NIP-01 OK "mute:" response telling the client the event was ignored. Default-off so
    // unhandled ephemeral events are simply acknowledged with a plain OK.
    var sendMuteResponse = false
    var listenToPokeyBroadcasts = true
    var startOnBoot = true
    var lastBackup: Long = 0L
    var useProxy = false
    var proxyAllUrls = false
    var useTor = false
    var onionHostname = ""
    var webClients = mutableMapOf<String, String>()

    // Installed nsites (NIP-5A static websites). Downloaded to filesDir/nsites/<folderName>
    // and served through the same localhost web-client mechanism as [webClients].
    var nsites: MutableList<NsiteInfo> = mutableListOf()

    // Epoch seconds of the last daily nsite update check. Gated like [lastBackup] so the
    // 100s service timer only runs the check roughly once per day.
    var lastNsiteCheck: Long = 0L

    // Relays queried to discover nsites. nsites use only this set (never the aggregator
    // relays); an empty list falls back to [DEFAULT_NSITE_RELAYS].
    var nsiteRelays: Set<String> = DEFAULT_NSITE_RELAYS

    var relayAggregatorEnabled = false
    var aggregatorPubkey = ""
    var relayAggregatorKinds: Set<Int> = setOf(0, 1, 3, 5, 6, 7, 1111, 10000, 10002, 30023)
    var relayAggregatorRefreshMinutes = 60
    var relayAggregatorIncludeTagged = true
    var relayAggregatorLastSync: Long = 0L

    // Relays the aggregator listens to when no pubkey is configured — plain kinds+since
    // subscription, no author filter. Also usable as an explicit extra relay set.
    var relayAggregatorExtraRelays: Set<String> = emptySet()

    // External aggregator-style relays (e.g. wss://aggr.nostr.land) that mirror events
    // from many upstreams. Subscribed for the full author set on every refresh, in
    // addition to each author's per-relay NIP-65 routing.
    var relayAggregatorSourceRelays: Set<String> = DEFAULT_AGGREGATOR_SOURCE_RELAYS

    // Relays consulted only to look up NIP-65 (kind 10002), contact-list (kind 3), and
    // mute-list (kind 10000) records when not already cached locally. An empty user
    // value falls back to DEFAULT_NIP65_INDEXER_RELAYS at read time so NIP-65 lookups
    // never silently break.
    var relayAggregatorIndexerRelays: Set<String> = DEFAULT_NIP65_INDEXER_RELAYS

    // When true, the aggregator suspends subscriptions whenever the active network is
    // metered (mobile data or metered Wi-Fi) and resumes once an unmetered network is
    // available again. Default-on because the aggregator holds many WebSockets open and
    // is the dominant battery cost on cellular.
    var relayAggregatorWifiOnly: Boolean = true

    // When true, the aggregator suspends subscriptions whenever the active network lacks
    // NET_CAPABILITY_NOT_RESTRICTED — i.e., a "limited"/restricted network such as a
    // captive-portal hotspot or an enterprise network that blocks general internet
    // traffic. Resumes automatically once an unrestricted network is available.
    var relayAggregatorPauseOnLimitedNetwork: Boolean = true

    // External signer used by the aggregator to answer NIP-42 AUTH challenges from upstream
    // relays. Populated by the "Log in with external signer" flow in settings. When both
    // fields are set, the aggregator installs a RelayAuthenticator and signs kind-22242
    // challenges via the configured Amber-compatible signer.
    var aggregatorSignerPubkey: String = ""
    var aggregatorSignerPackageName: String = ""

    fun defaultValues() {
        allowedKinds = emptySet()
        rejectedKinds = DEFAULT_REJECTED_KINDS
        allowedPubKeys = emptySet()
        allowedTaggedPubKeys = emptySet()
        deleteEventsOlderThan = OlderThan.NEVER
        useSSL = false
        host = "127.0.0.1"
        port = 4869
        neverDeleteFrom = emptySet()
        preservedKindsFromDeletion = setOf(0, 3, 10000, 10002)
        name = "Citrine"
        ownerPubkey = ""
        contact = ""
        description = "A Nostr relay in your phone"
        relayIcon = "https://github.com/greenart7c3/Citrine/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
        autoBackup = false
        autoBackupFolder = ""
        relaySignerPackageName = ""
        sendMuteResponse = false
        listenToPokeyBroadcasts = true
        startOnBoot = true
        lastBackup = 0L
        useProxy = false
        proxyAllUrls = false
        useTor = false
        onionHostname = ""
        webClients = mutableMapOf()
        nsites = mutableListOf()
        lastNsiteCheck = 0L
        nsiteRelays = DEFAULT_NSITE_RELAYS
        relayAggregatorEnabled = false
        aggregatorPubkey = ""
        relayAggregatorKinds = setOf(0, 1, 3, 5, 6, 7, 1111, 10000, 10002, 30023)
        relayAggregatorRefreshMinutes = 60
        relayAggregatorIncludeTagged = true
        relayAggregatorLastSync = 0L
        relayAggregatorExtraRelays = emptySet()
        relayAggregatorSourceRelays = DEFAULT_AGGREGATOR_SOURCE_RELAYS
        relayAggregatorIndexerRelays = DEFAULT_NIP65_INDEXER_RELAYS
        relayAggregatorWifiOnly = true
        relayAggregatorPauseOnLimitedNetwork = true
        aggregatorSignerPubkey = ""
        aggregatorSignerPackageName = ""
    }

    fun webClientFromJson(json: String): MutableMap<String, String> = JacksonMapper.mapper.readValue<MutableMap<String, String>>(json)

    fun webClientsToJson(): String = JacksonMapper.mapper.writeValueAsString(webClients)

    fun nsitesFromJson(json: String): MutableList<NsiteInfo> = JacksonMapper.mapper.readValue<MutableList<NsiteInfo>>(json)

    fun nsitesToJson(): String = JacksonMapper.mapper.writeValueAsString(nsites)
}

/**
 * An installed nsite (NIP-5A static website).
 *
 * [address] is the canonical identifier: "15128:<pubkey>" for a root site or
 * "35128:<pubkey>:<dTag>" for a named site. [folderName] is a filesystem-safe slug
 * (prefixed with "nsite_") used both as the on-disk directory under filesDir/nsites and
 * as the "<folderName>.localhost" subdomain the relay serves it from. [aggregateHash] is
 * the last-applied ["x", <hash>, "aggregate"] value, compared against a freshly fetched
 * manifest to detect updates.
 */
data class NsiteInfo(
    val address: String = "",
    val pubkey: String = "",
    val kind: Int = 0,
    val dTag: String = "",
    val displayName: String = "",
    val folderName: String = "",
    val aggregateHash: String = "",
    val autoUpdate: Boolean = false,
    val lastChecked: Long = 0L,
)

enum class OlderThan {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    NEVER,
}

enum class OlderThanType(val screenCode: Int, val resourceId: Int) {
    NEVER(0, R.string.never),
    DAY(1, R.string.day),
    WEEK(2, R.string.one_week),
    MONTH(3, R.string.one_month),
    YEAR(4, R.string.one_year),
}
