package com.greenart7c3.citrine.server

object Settings {
    var allowedKinds: Set<Int> = emptySet()
    var allowedPubKeys: Set<String> = emptySet()
    var allowedTaggedPubKeys: Set<String> = emptySet()
    var deleteEventsOlderThan: OlderThan = OlderThan.NEVER
    var deleteExpiredEvents: Boolean = true
    var deleteEphemeralEvents: Boolean = true
    var host: String = "127.0.0.1"
    var port: Int = 4869
}

enum class OlderThan {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    NEVER,
}
