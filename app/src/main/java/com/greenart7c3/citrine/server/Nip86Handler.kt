package com.greenart7c3.citrine.server

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.utils.TimeUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first

object Nip86Handler {
    private const val AUTH_KIND = 27235
    private const val AUTH_TIME_WINDOW_SEC = 60L
    private const val MAX_BODY_BYTES = 64 * 1024
    private val RPC_CONTENT_TYPE = ContentType.parse("application/nostr+json+rpc")

    private val SUPPORTED_METHODS = listOf(
        "supportedmethods",
        "banpubkey",
        "listbannedpubkeys",
        "allowpubkey",
        "listallowedpubkeys",
        "banevent",
        "listbannedevents",
        "allowevent",
        "listeventsneedingmoderation",
        "changerelayname",
        "changerelaydescription",
        "changerelayicon",
        "allowkind",
        "disallowkind",
        "listallowedkinds",
        "blockip",
        "unblockip",
        "listblockedips",
        "stats",
    )

    data class RpcRequest(
        val method: String = "",
        val params: List<JsonNode> = emptyList(),
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class RpcResponse(
        val result: Any? = null,
        val error: String? = null,
    )

    suspend fun handle(call: ApplicationCall, db: AppDatabase, context: Context) {
        if (Settings.ownerPubkey.isBlank()) {
            respondError(call, HttpStatusCode.NotFound, "relay management disabled")
            return
        }

        val contentType = call.request.contentType()
        if (!contentType.match(RPC_CONTENT_TYPE)) {
            respondError(call, HttpStatusCode.UnsupportedMediaType, "unsupported content type")
            return
        }

        val body = call.receive<ByteArray>()
        if (body.size > MAX_BODY_BYTES) {
            respondError(call, HttpStatusCode.PayloadTooLarge, "body too large")
            return
        }

        val fullUrl = buildFullUrl(call)
        val authPubkey = verifyNip98(
            call.request.headers[HttpHeaders.Authorization],
            call.request.httpMethod.value,
            fullUrl,
            body,
        )
        if (authPubkey == null) {
            respondError(call, HttpStatusCode.Unauthorized, "unauthorized")
            return
        }
        if (authPubkey != Settings.ownerPubkey) {
            respondError(call, HttpStatusCode.Forbidden, "forbidden")
            return
        }

        val rpc = try {
            JacksonMapper.mapper.readValue(body, RpcRequest::class.java)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "NIP-86 invalid request body", e)
            respondJson(call, RpcResponse(error = "invalid request body"))
            return
        }

        val response = try {
            val result: Any? = dispatch(rpc, db, context)
            RpcResponse(result = result)
        } catch (e: UnknownMethodException) {
            RpcResponse(error = "unknown method: ${e.method}")
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "NIP-86 method '${rpc.method}' failed", e)
            RpcResponse(error = e.message ?: "internal error")
        }
        respondJson(call, response)
    }

    private class UnknownMethodException(val method: String) : RuntimeException()

    private suspend fun dispatch(rpc: RpcRequest, db: AppDatabase, ctx: Context): Any? = when (rpc.method) {
        "supportedmethods" -> SUPPORTED_METHODS
        "banpubkey" -> banPubkey(rpc.params, db, ctx)
        "listbannedpubkeys" -> listBannedPubkeys()
        "allowpubkey" -> allowPubkey(rpc.params, ctx)
        "listallowedpubkeys" -> listAllowedPubkeys()
        "banevent" -> banEvent(rpc.params, db, ctx)
        "listbannedevents" -> listBannedEvents()
        "allowevent" -> allowEvent(rpc.params, ctx)
        "listeventsneedingmoderation" -> emptyList<Any>()
        "changerelayname" -> changeRelayName(rpc.params, ctx)
        "changerelaydescription" -> changeRelayDescription(rpc.params, ctx)
        "changerelayicon" -> changeRelayIcon(rpc.params, ctx)
        "allowkind" -> allowKind(rpc.params, ctx)
        "disallowkind" -> disallowKind(rpc.params, ctx)
        "listallowedkinds" -> Settings.allowedKinds.sorted()
        "blockip" -> blockIp(rpc.params, ctx)
        "unblockip" -> unblockIp(rpc.params, ctx)
        "listblockedips" -> listBlockedIps()
        "stats" -> stats(db)
        else -> throw UnknownMethodException(rpc.method)
    }

    // ---------------------------------------------------------------------
    // Method handlers
    // ---------------------------------------------------------------------

    private suspend fun banPubkey(params: List<JsonNode>, db: AppDatabase, ctx: Context): Boolean {
        val pubkey = stringParam(params, 0, "pubkey")
        val reason = stringParamOrEmpty(params, 1)
        Settings.bannedPubKeys[pubkey] = reason
        Settings.allowedPubKeys = Settings.allowedPubKeys - pubkey
        db.eventDao().deleteByPubkey(pubkey)
        persist(ctx)
        return true
    }

    private fun listBannedPubkeys(): List<Map<String, String>> = Settings.bannedPubKeys.map { (pubkey, reason) -> mapOf("pubkey" to pubkey, "reason" to reason) }

    private fun allowPubkey(params: List<JsonNode>, ctx: Context): Boolean {
        val pubkey = stringParam(params, 0, "pubkey")
        Settings.bannedPubKeys.remove(pubkey)
        Settings.allowedPubKeys = Settings.allowedPubKeys + pubkey
        persist(ctx)
        return true
    }

    private fun listAllowedPubkeys(): List<Map<String, String>> = Settings.allowedPubKeys.map { mapOf("pubkey" to it) }

    private suspend fun banEvent(params: List<JsonNode>, db: AppDatabase, ctx: Context): Boolean {
        val id = stringParam(params, 0, "id")
        val reason = stringParamOrEmpty(params, 1)
        Settings.bannedEventIds[id] = reason
        db.eventDao().delete(listOf(id))
        persist(ctx)
        return true
    }

    private fun listBannedEvents(): List<Map<String, String>> = Settings.bannedEventIds.map { (id, reason) -> mapOf("id" to id, "reason" to reason) }

    private fun allowEvent(params: List<JsonNode>, ctx: Context): Boolean {
        val id = stringParam(params, 0, "id")
        Settings.bannedEventIds.remove(id)
        persist(ctx)
        return true
    }

    private fun changeRelayName(params: List<JsonNode>, ctx: Context): Boolean {
        Settings.name = stringParam(params, 0, "name")
        persist(ctx)
        return true
    }

    private fun changeRelayDescription(params: List<JsonNode>, ctx: Context): Boolean {
        Settings.description = stringParam(params, 0, "description")
        persist(ctx)
        return true
    }

    private fun changeRelayIcon(params: List<JsonNode>, ctx: Context): Boolean {
        Settings.relayIcon = stringParam(params, 0, "icon")
        persist(ctx)
        return true
    }

    private fun allowKind(params: List<JsonNode>, ctx: Context): Boolean {
        val kind = intParam(params, 0, "kind")
        Settings.allowedKinds = Settings.allowedKinds + kind
        persist(ctx)
        return true
    }

    private fun disallowKind(params: List<JsonNode>, ctx: Context): Boolean {
        val kind = intParam(params, 0, "kind")
        Settings.allowedKinds = Settings.allowedKinds - kind
        persist(ctx)
        return true
    }

    private fun blockIp(params: List<JsonNode>, ctx: Context): Boolean {
        val ip = stringParam(params, 0, "ip")
        val reason = stringParamOrEmpty(params, 1)
        Settings.blockedIps[ip] = reason
        persist(ctx)
        return true
    }

    private fun unblockIp(params: List<JsonNode>, ctx: Context): Boolean {
        val ip = stringParam(params, 0, "ip")
        Settings.blockedIps.remove(ip)
        persist(ctx)
        return true
    }

    private fun listBlockedIps(): List<Map<String, String>> = Settings.blockedIps.map { (ip, reason) -> mapOf("ip" to ip, "reason" to reason) }

    private suspend fun stats(db: AppDatabase): Map<String, Any> {
        // countByKind returns a Flow — sample the first emission.
        val snapshot = db.eventDao().countByKind().first()
        val total = snapshot.sumOf { it.count.toLong() }
        return mapOf(
            "events" to total,
            "kinds" to snapshot.associate { it.kind.toString() to it.count },
        )
    }

    // ---------------------------------------------------------------------
    // NIP-98 verification
    // ---------------------------------------------------------------------

    fun verifyNip98(authHeader: String?, method: String, fullUrl: String, body: ByteArray): String? {
        if (authHeader == null || !authHeader.startsWith("Nostr ")) return null
        val b64 = authHeader.removePrefix("Nostr ").trim()
        val json = try {
            String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "NIP-98 invalid base64", e)
            return null
        }
        val event = try {
            Event.fromJson(json)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "NIP-98 invalid event json", e)
            return null
        }
        if (event.kind != AUTH_KIND) {
            Log.d(Citrine.TAG, "NIP-98 wrong kind ${event.kind}")
            return null
        }
        val now = TimeUtils.now()
        if (event.createdAt > now + AUTH_TIME_WINDOW_SEC || event.createdAt < now - AUTH_TIME_WINDOW_SEC) {
            Log.d(Citrine.TAG, "NIP-98 timestamp out of window: createdAt=${event.createdAt} now=$now")
            return null
        }

        val uTag = event.tags.firstOrNull { it.size > 1 && it[0] == "u" }?.get(1)
        val mTag = event.tags.firstOrNull { it.size > 1 && it[0] == "method" }?.get(1)
        if (uTag == null || !uTag.equals(fullUrl, ignoreCase = true)) {
            Log.d(Citrine.TAG, "NIP-98 u tag mismatch expected=$fullUrl got=$uTag")
            return null
        }
        if (mTag == null || !mTag.equals(method, ignoreCase = true)) {
            Log.d(Citrine.TAG, "NIP-98 method tag mismatch expected=$method got=$mTag")
            return null
        }

        if (body.isNotEmpty()) {
            val payloadTag = event.tags.firstOrNull { it.size > 1 && it[0] == "payload" }?.get(1)
                ?: run {
                    Log.d(Citrine.TAG, "NIP-98 missing payload tag")
                    return null
                }
            val hex = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it) }
            if (!payloadTag.equals(hex, ignoreCase = true)) {
                Log.d(Citrine.TAG, "NIP-98 payload hash mismatch")
                return null
            }
        }

        if (!event.verify()) {
            Log.d(Citrine.TAG, "NIP-98 signature verify failed")
            return null
        }
        return event.pubKey
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun buildFullUrl(call: ApplicationCall): String {
        val scheme = call.request.origin.scheme
        val rawHost = call.request.host()
        val hostNoPort = rawHost.substringBefore(":")
        val port = call.request.origin.serverPort
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val authority = if (isDefaultPort) hostNoPort else "$hostNoPort:$port"
        return "$scheme://$authority${call.request.uri}"
    }

    private fun stringParam(params: List<JsonNode>, index: Int, name: String): String {
        val node = params.getOrNull(index) ?: throw IllegalArgumentException("missing param: $name")
        if (!node.isTextual) throw IllegalArgumentException("param $name must be a string")
        return node.asText()
    }

    private fun stringParamOrEmpty(params: List<JsonNode>, index: Int): String = params.getOrNull(index)?.takeIf { it.isTextual }?.asText() ?: ""

    private fun intParam(params: List<JsonNode>, index: Int, name: String): Int {
        val node = params.getOrNull(index) ?: throw IllegalArgumentException("missing param: $name")
        if (!node.isInt && !node.canConvertToInt()) {
            throw IllegalArgumentException("param $name must be an integer")
        }
        return node.asInt()
    }

    private fun persist(ctx: Context) {
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, ctx)
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
        call.respondText(
            JacksonMapper.mapper.writeValueAsString(RpcResponse(error = message)),
            ContentType.Application.Json,
            status,
        )
    }

    private suspend fun respondJson(call: ApplicationCall, response: RpcResponse) {
        call.respondText(
            JacksonMapper.mapper.writeValueAsString(response),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}
