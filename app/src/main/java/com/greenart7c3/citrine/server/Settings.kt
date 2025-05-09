package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.R

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
    }
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
