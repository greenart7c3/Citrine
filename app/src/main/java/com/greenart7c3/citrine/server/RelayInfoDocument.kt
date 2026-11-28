package com.greenart7c3.citrine.server

import android.content.Context
import com.fasterxml.jackson.databind.node.ObjectNode
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.json.JacksonMapper
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayIdentity
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.respondText

internal suspend fun respondRelayRoot(call: ApplicationCall, context: Context) {
    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
    call.response.headers.appendIfAbsent("Access-Control-Allow-Credentials", "true")
    call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "*")
    call.response.headers.appendIfAbsent("Access-Control-Allow-Headers", "*")
    call.response.headers.appendIfAbsent("Access-Control-Expose-Headers", "*")

    if (call.request.httpMethod == HttpMethod.Options) {
        call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
    } else if (call.request.headers["Accept"] == "application/nostr+json") {
        LocalPreferences.loadSettingsFromEncryptedStorage(context)

        call.respondText(
            JacksonMapper.mapper.writeValueAsString(relayInformationDocument()),
            ContentType.Application.Json,
        )
    } else {
        call.respondText(
            "Use a Nostr client or Websocket client to connect",
            ContentType.Text.Html,
        )
    }
}

private fun relayInformationDocument(): ObjectNode {
    val supportedNips = mutableListOf(1, 2, 9, 11, 29, 40, 42, 45, 50, 59, 65, 70, 77)
    if (Settings.ownerPubkey.isNotBlank()) supportedNips.add(86)

    // NIP-29 clients discover the relay's group-signing key via the
    // "self" field: the owner's key when configured, otherwise the
    // relay's generated identity.
    val relayPubkey = RelayIdentity.pubKeyHex()

    return JacksonMapper.mapper.createObjectNode().apply {
        put("name", Settings.name)
        put("description", Settings.description)
        put("pubkey", Settings.ownerPubkey.ifBlank { relayPubkey })
        put("contact", Settings.contact)
        put("self", relayPubkey)
        val nips = putArray("supported_nips")
        supportedNips.forEach { nips.add(it) }
        put("software", "https://github.com/greenart7c3/Citrine")
        put("version", BuildConfig.VERSION_NAME)
        put("icon", Settings.relayIcon)
        if (Settings.maxEventSize > 0) {
            putObject("limitation").put("max_content_length", Settings.maxEventSize)
        }
    }
}
