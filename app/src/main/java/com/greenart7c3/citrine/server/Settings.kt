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
