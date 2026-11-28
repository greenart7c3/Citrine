package com.greenart7c3.citrine.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.json.JacksonMapper
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CancellationException

private fun TimeUtils.tenMinutesAgo(): Long {
    val tenMinutes = 10 * ONE_MINUTE
    return now() - tenMinutes
}

private fun TimeUtils.tenMinutesAhead(): Long {
    val tenMinutes = 10 * ONE_MINUTE
    return now() + tenMinutes
}

private fun isUnset(value: Int?): Boolean = value == null || value == 0

private fun EventFilter.isUnbounded(): Boolean = isUnset(since) && isUnset(until) && limit == null

internal class RelayMessageHandler(
    private val server: CustomWebSocketServer,
    private val appDatabase: AppDatabase,
    private val objectMapper: ObjectMapper,
) {
    suspend fun process(newMessage: String, connection: Connection?) {
        try {
            if (Log.isLoggable(Citrine.TAG, Log.DEBUG)) {
                Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
            }
            val msgArray = JacksonMapper.mapper.readTree(newMessage)
            dispatch(msgArray, connection)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.toString(), e)
            try {
                connection?.send(NoticeResult.invalid("Error processing message").toJson())
            } catch (e: Exception) {
                Log.d(Citrine.TAG, e.toString(), e)
            }
        }
    }

    private suspend fun dispatch(msgArray: JsonNode, connection: Connection?) {
        when (val type = msgArray.get(0).asText()) {
            "COUNT" -> onSubscribe(msgArray, connection, count = true)
            "REQ" -> onSubscribe(msgArray, connection, count = false)
            "AUTH" -> onAuth(msgArray, connection)
            "EVENT" -> onEvent(msgArray, connection)
            "CLOSE" -> onClose(msgArray, connection)
            "NEG-OPEN" -> onNegOpen(msgArray, connection)
            "NEG-MSG" -> onNegMsg(msgArray, connection)
            "NEG-CLOSE" -> onNegClose(msgArray, connection)
            "PING" -> onPing(connection)
            else -> onUnknown(type, connection)
        }
    }

    private suspend fun onSubscribe(msgArray: JsonNode, connection: Connection?, count: Boolean) {
        connection?.let {
            val subscriptionId = msgArray.get(1).asText()
            subscribe(
                subscriptionId = subscriptionId,
                filterNodes = msgArray.drop(2),
                connection = it,
                count = count,
            )
        }
    }

    private suspend fun onAuth(msgArray: JsonNode, connection: Connection?) {
        val event = Event.fromJson(msgArray.get(1).toString())
        val exception = validateAuthEvent(event, connection?.authChallenge ?: "").exceptionOrNull()
        if (exception != null) {
            Log.d(Citrine.TAG, exception.message!!)
            connection?.send(CommandResult.invalid(event, exception.message!!).toJson())
            return
        }
        // NIP-42 (https://github.com/nostr-protocol/nips/blob/master/42.md): without a valid signature any client could authenticate as any pubkey.
        if (!event.verify()) {
            connection?.send(CommandResult.invalid(event, "auth event signature verification failed").toJson())
            return
        }

        Log.d(Citrine.TAG, "AUTH successful ${event.toJson()}")
        connection?.users?.add(event.pubKey)
        connection?.send(CommandResult.ok(event).toJson())
    }

    private suspend fun onEvent(msgArray: JsonNode, connection: Connection?) {
        val event = Event.fromJson(msgArray.get(1).toString())
        server.innerProcessEvent(event, connection)
    }

    private fun onClose(msgArray: JsonNode, connection: Connection?) {
        connection?.let {
            EventSubscription.close(it, msgArray.get(1).asText())
        }
    }

    private suspend fun onNegOpen(msgArray: JsonNode, connection: Connection?) {
        connection?.let {
            NegentropyHandler.handleOpen(
                connection = it,
                subId = msgArray.get(1).asText(),
                filterNode = msgArray.get(2),
                initialMsgHex = msgArray.get(3).asText(),
                appDatabase = appDatabase,
                objectMapper = objectMapper,
            )
        }
    }

    private fun onNegMsg(msgArray: JsonNode, connection: Connection?) {
        connection?.let {
            NegentropyHandler.handleMsg(
                connection = it,
                subId = msgArray.get(1).asText(),
                msgHex = msgArray.get(2).asText(),
                objectMapper = objectMapper,
            )
        }
    }

    private fun onNegClose(msgArray: JsonNode, connection: Connection?) {
        connection?.let {
            NegentropyHandler.handleClose(it, msgArray.get(1).asText())
        }
    }

    private suspend fun onPing(connection: Connection?) {
        try {
            connection?.send(NoticeResult("PONG").toJson())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Failed to send pong response", e)
        }
    }

    private suspend fun onUnknown(type: String, connection: Connection?) {
        try {
            val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
            Log.d(Citrine.TAG, errorMessage)
            connection?.send(errorMessage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Failed to send response", e)
        }
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        connection: Connection,
        count: Boolean = false,
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.properties().asSequence()
                .filter { it.key.startsWith("#") }.associate { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }

            val filter = objectMapper.treeToValue(jsonNode, EventFilter::class.java)

            var limit = filter.limit
            if (filter.isUnbounded()) {
                Log.d(Citrine.TAG, "No filter provided for subscription $subscriptionId filter $jsonNode, setting limit to 1_000")
                limit = 1_000
            }
            filter.copy(
                tags = tags,
                limit = limit,
            )
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, connection, appDatabase, objectMapper, count)
    }

    private fun validateAuthEvent(event: Event, challenge: String): Result<Boolean> {
        if (event.kind != RelayAuthEvent.KIND) {
            Log.d(Citrine.TAG, "incorrect event kind for auth")
            return Result.failure(Exception("incorrect event kind for auth"))
        }

        val eventChallenge = (event as RelayAuthEvent).challenge()
        val eventRelay = event.relay()
        if (eventChallenge.isNullOrBlank()) {
            Log.d(Citrine.TAG, "no challenge in auth event ${event.toJson()}")
            return Result.failure(Exception("no challenge in auth event"))
        }

        if (eventRelay?.url.isNullOrBlank()) {
            Log.d(Citrine.TAG, "no relay in auth event ${event.toJson()}")
            return Result.failure(Exception("no relay in auth event"))
        }

        if (eventChallenge != challenge) {
            Log.d(Citrine.TAG, "challenge mismatch ${event.toJson()}")
            return Result.failure(Exception("challenge mismatch"))
        }

        val formattedRelayUrl = RelayUrlNormalizer.normalizeOrNull(eventRelay.url)
        if (formattedRelayUrl == null) {
            Log.d(Citrine.TAG, "invalid relay url ${event.toJson()}")
            return Result.failure(Exception("invalid relay url"))
        }

        if (event.createdAt > TimeUtils.tenMinutesAhead() || event.createdAt < TimeUtils.tenMinutesAgo()) {
            Log.d(Citrine.TAG, "auth event more than 10 minutes before or after current time ${event.toJson()}")
            return Result.failure(Exception("auth event more than 10 minutes before or after current time"))
        }

        return Result.success(true)
    }
}
