package com.greenart7c3.citrine.utils

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun Long.toDateString(): String {
    val dateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(this * 1000),
        ZoneId.systemDefault()
    )

    // Format the LocalDateTime with local format
    val formatter = DateTimeFormatter.ofLocalizedDateTime(
        FormatStyle.SHORT
    )

    return formatter.format(dateTime)
}
