package com.greenart7c3.citrine

import com.vitorpamplona.quartz.events.Event

private const val KIND_EVENT_DELETION = 5
private val KINDS_EVENT_EPHEMERAL = 20000 until 30000
private val KINDS_EVENT_REPLACEABLE = setOf(
    0..0,
    3..3,
    10000 until 20000
)

fun Event.shouldDelete(): Boolean = kind == KIND_EVENT_DELETION
fun Event.isEphemeral(): Boolean = KINDS_EVENT_EPHEMERAL.contains(kind)
fun Event.shouldOverwrite(): Boolean = KINDS_EVENT_REPLACEABLE.any { it.contains(kind) }