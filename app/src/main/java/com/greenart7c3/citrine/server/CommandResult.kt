package com.greenart7c3.citrine.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitorpamplona.quartz.nip01Core.core.Event

data class CommandResult(val eventId: String, val result: Boolean, val description: String = "") {
    fun toJson(): String = jacksonObjectMapper().writeValueAsString(
        listOf("OK", eventId, result, description),
    )

    companion object {
        fun ok(event: Event) = CommandResult(event.id, true)
        fun duplicated(event: Event) = CommandResult(event.id, true, "duplicate:")
        fun invalid(event: Event, message: String) = CommandResult(event.id, false, "invalid: $message")
        fun required(event: Event, message: String) = CommandResult(event.id, false, "auth-required: $message")

        fun mute(event: Event) = CommandResult(event.id, false, "mute: no one was listening to your ephemeral event and it wasn't handled in any way, it was ignored")
    }
}

data class ClosedResult(val subId: String, val message: String) {
    fun toJson(): String = jacksonObjectMapper().writeValueAsString(
        listOf("CLOSED", subId, message),
    )

    companion object {
        fun required(subId: String) = ClosedResult(subId, "auth-required: we only accept events from registered users")
        fun restricted(subId: String) = ClosedResult(subId, "restricted: we only accept events from registered users")
    }
}
