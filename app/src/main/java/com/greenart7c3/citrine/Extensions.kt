package com.greenart7c3.citrine

import com.vitorpamplona.quartz.events.Event

const val KIND_EVENT_DELETION = 5

fun Event.shouldDelete(): Boolean = kind == KIND_EVENT_DELETION