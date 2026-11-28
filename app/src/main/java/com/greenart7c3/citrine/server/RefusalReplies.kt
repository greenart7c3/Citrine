package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.server.CustomWebSocketServer.VerificationResult
import com.vitorpamplona.quartz.nip01Core.core.Event

internal suspend fun Connection.sendRefusal(event: Event, result: VerificationResult) {
    if (result == VerificationResult.AuthRequiredForProtectedEvent) {
        send(AuthResult.challenge(authChallenge).toJson())
        send(CommandResult.required(event, "this event may only be published by its author").toJson())
        return
    }
    val reply = result.refusal?.invoke(event) ?: return
    send(reply.toJson())
}
