package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.server.CustomWebSocketServer.VerificationResult
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationResultRefusalTest {
    private val event = Event(
        id = "e".repeat(64),
        pubKey = "p".repeat(64),
        createdAt = 1L,
        kind = 7,
        tags = emptyArray(),
        content = "",
        sig = "s".repeat(128),
    )

    private fun reply(result: VerificationResult): CommandResult? = result.refusal?.invoke(event)

    @Test
    fun onlyAcceptedAndSeparatelyAnsweredResultsHaveNoRefusal() {
        val silent = VerificationResult.entries.filter { it.refusal == null }.toSet()
        assertEquals(
            setOf(VerificationResult.Valid, VerificationResult.RejectedByNip29, VerificationResult.AuthRequiredForProtectedEvent),
            silent,
        )
    }

    @Test
    fun refusalsKeepTheirOkTexts() {
        assertEquals(CommandResult(event.id, false, "invalid: event id hash verification failed"), reply(VerificationResult.InvalidId))
        assertEquals(CommandResult(event.id, false, "invalid: event expired"), reply(VerificationResult.Expired))
        assertEquals(CommandResult(event.id, false, "blocked: kind 7 is not accepted by this relay"), reply(VerificationResult.KindRejected))
        assertEquals(CommandResult(event.id, false, "deleted: event has been deleted by its author"), reply(VerificationResult.Deleted))
        assertEquals(CommandResult.duplicated(event), reply(VerificationResult.AlreadyInDatabase))
        assertEquals(
            CommandResult(event.id, false, "duplicate: a newer event already exists for this replaceable"),
            reply(VerificationResult.NewestEventAlreadyInDatabase),
        )
        assertNull(reply(VerificationResult.Valid))
    }
}
