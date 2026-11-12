/**
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.greenart7c3.citrine.service.crashreports

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Delivers a crash report to the developer as a NIP-17 DM, the same way Amber does: one gift wrap
 * per recipient, published to the developer's inbox relays, expiring after 30 days.
 *
 * Citrine holds no user key, so the caller picks the identity: the user's own key through an
 * external signer (the developer can reply) or a throwaway one for an anonymous report.
 */
object CrashReportSender {
    /** The developer's key. Same account as Amber's report inbox. */
    const val DEVELOPER_HEX_KEY = "7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19"

    private const val THIRTY_DAYS_IN_SECONDS = 30L * 86_400

    private val RELAYS = setOf(
        NormalizedRelayUrl("wss://inbox.nostr.wine"),
        NormalizedRelayUrl("wss://nostr.land"),
    )

    /**
     * Encrypts, signs and publishes [report]. Returns true when at least one relay accepted a wrap.
     *
     * Uses its own [NostrClient] (with the app's Tor/proxy policy through the shared factory)
     * because the shared client may be disconnected by background work while this is in flight.
     */
    suspend fun send(report: String, signer: NostrSigner): Boolean {
        if (report.isBlank()) return false

        val client = NostrClient(Citrine.instance.factory, Citrine.instance.applicationScope)

        return try {
            client.connect()

            val template = ChatMessageEvent.build(
                msg = report,
                to = listOf(PTag(DEVELOPER_HEX_KEY)),
                createdAt = TimeUtils.now(),
            ) {
                expiration(TimeUtils.now() + THIRTY_DAYS_IN_SECONDS)
            }

            val wraps = NIP17Factory().createMessageNIP17(template, signer).wraps
            // The sender's self-copy is useless here: only the developer's wrap needs to go out.
            val addressed = wraps.filter { it.recipientPubKey() == DEVELOPER_HEX_KEY }
            val outbox = addressed.ifEmpty { wraps }

            coroutineScope {
                outbox
                    .map { wrap -> async { client.publishAndConfirm(wrap, relayList = RELAYS) } }
                    .awaitAll()
                    .any { it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Could not send the crash report", e)
            false
        } finally {
            client.disconnect()
        }
    }

    /** A throwaway identity for reports the user does not want tied to their npub. */
    fun anonymousSigner(): NostrSigner = NostrSignerInternal(KeyPair())
}
