package com.greenart7c3.citrine.server

import com.fasterxml.jackson.module.kotlin.readValue
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper

object Settings {
    var allowedKinds: Set<Int> = emptySet()
    var allowedPubKeys: Set<String> = emptySet()
    var allowedTaggedPubKeys: Set<String> = emptySet()
    var deleteEventsOlderThan: OlderThan = OlderThan.NEVER
    var deleteExpiredEvents: Boolean = true
    var deleteEphemeralEvents: Boolean = true
    var useSSL: Boolean = false
    var host: String = "127.0.0.1"
    var port: Int = 4869
    var neverDeleteFrom: Set<String> = emptySet()
    var name: String = "Citrine"
    var ownerPubkey: String = ""
    var contact: String = ""
    var description: String = "A Nostr relay in your phone"
    var relayIcon: String = "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
    var autoBackup = false
    var autoBackupFolder = ""
    var authEnabled = false
    var listenToPokeyBroadcasts = true
    var startOnBoot = true
    var lastBackup: Long = 0L
    var useProxy = false
    var proxyPort = 9050
    var webClients = mutableMapOf<String, String>()
    var databaseBackend: DatabaseBackend = DatabaseBackend.ROOM

    fun defaultValues() {
        allowedKinds = emptySet()
        allowedPubKeys = emptySet()
        allowedTaggedPubKeys = emptySet()
        deleteEventsOlderThan = OlderThan.NEVER
        deleteExpiredEvents = true
        deleteEphemeralEvents = true
        useSSL = false
        host = "127.0.0.1"
        port = 4869
        neverDeleteFrom = emptySet()
        name = "Citrine"
        ownerPubkey = ""
        contact = ""
        description = "A Nostr relay in your phone"
        relayIcon = "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
        autoBackup = false
        autoBackupFolder = ""
        authEnabled = false
        listenToPokeyBroadcasts = true
        startOnBoot = true
        lastBackup = 0L
        useProxy = false
        proxyPort = 9050
        webClients = mutableMapOf()
        databaseBackend = DatabaseBackend.ROOM
    }

    fun webClientFromJson(json: String): MutableMap<String, String> = JacksonMapper.mapper.readValue<MutableMap<String, String>>(json)

    fun webClientsToJson(): String = JacksonMapper.mapper.writeValueAsString(webClients)
}

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

/**
 * The storage backend used for persisting Nostr events.
 *
 * Changing this setting requires restarting the relay service. Data is NOT automatically
 * migrated between backends.
 */
enum class DatabaseBackend {
    /** Android Room ORM backed by SQLite (default). Production-ready. */
    ROOM,

    /**
     * NostrDB — a high-performance C library for Nostr event storage.
     * Requires the `nostrdb-android` native library.
     * See [com.greenart7c3.citrine.database.NostrDbEventStore] for setup instructions.
     */
    NOSTRDB,

    /**
     * rust-nostr LMDB — Rust-based LMDB storage from the rust-nostr SDK.
     * Requires the `nostr-sdk-android` native library.
     * See [com.greenart7c3.citrine.database.RustNostrEventStore] for setup instructions.
     */
    RUST_NOSTR_LMDB,
}
