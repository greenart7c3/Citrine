package com.greenart7c3.citrine.server

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.HistoryDatabase
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.provider.CitrineContract
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.server.nip29.Nip29Handler
import com.greenart7c3.citrine.server.nip29.Nip29Result
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.EventBroadcastWorker
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.utils.isEphemeral
import com.greenart7c3.citrine.utils.isParameterizedReplaceable
import com.greenart7c3.citrine.utils.shouldDelete
import com.greenart7c3.citrine.utils.shouldOverwrite
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.aTag.taggedAddresses
import com.vitorpamplona.quartz.nip01Core.tags.events.taggedEvents
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip70ProtectedEvts.isProtected
import com.vitorpamplona.quartz.utils.TimeUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.copyTo
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CustomWebSocketServer(
    private val host: String,
    private val port: Int,
    private val appDatabase: AppDatabase,
) {
    val connections = MutableStateFlow<List<Connection>>(emptyList())
    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private val webClientServers = ConcurrentHashMap<String, WebClientServer>()
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun start() {
        val serverSocket = try {
            ServerSocket(Settings.port)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.toString(), e)
            Toast.makeText(Citrine.instance, "Port ${Settings.port} is already in use", Toast.LENGTH_LONG).show()
            null
        }
        if (serverSocket == null) return
        serverSocket.close()

        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                GroupManager.load(appDatabase)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Citrine.TAG, "Failed to load NIP-29 groups", e)
            }
        }

        Log.d(Citrine.TAG, "Starting server on $host:$port isInitialized: ${::server.isInitialized}")
        if (!::server.isInitialized) {
            server = startKtorHttpServer(host, port)
            server.monitor.subscribe(ApplicationStarted) {
                Log.d(Citrine.TAG, "Server started on $host:$port")
                CustomWebSocketService.hasStarted = true
            }

            server.monitor.subscribe(ApplicationStopped) {
                Log.d(Citrine.TAG, "Server stopped")
                CustomWebSocketService.hasStarted = false
            }
            server.start(false)
        } else {
            server.start(false)
            startWebClients()
        }
    }

    fun stop() {
        Log.d(Citrine.TAG, "Stopping server")
        if (::server.isInitialized) {
            val snapshot = connections.value.toList()
            runBlocking {
                withTimeoutOrNull(5_000) {
                    snapshot.forEach { conn ->
                        try {
                            removeConnection(conn)
                        } catch (e: Throwable) {
                            Log.w(Citrine.TAG, "Error removing ${conn.name} during stop", e)
                        }
                    }
                }
            }
            EventSubscription.closeAll()
            server.stop()
            webClientServers.forEach {
                it.value.server.stop()
            }
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
            if ((filter.since == null || filter.since == 0) && (filter.until == null || filter.until == 0) && filter.limit == null) {
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

    fun TimeUtils.tenMinutesAgo(): Long {
        val tenMinutes = 10 * ONE_MINUTE
        return now() - tenMinutes
    }

    fun TimeUtils.tenMinutesAhead(): Long {
        val tenMinutes = 10 * ONE_MINUTE
        return now() + tenMinutes
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

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection?) {
        try {
            if (Log.isLoggable(Citrine.TAG, Log.DEBUG)) {
                Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
            }
            val msgArray = JacksonMapper.mapper.readTree(newMessage)
            when (val type = msgArray.get(0).asText()) {
                "COUNT" -> {
                    connection?.let {
                        val subscriptionId = msgArray.get(1).asText()
                        subscribe(
                            subscriptionId = subscriptionId,
                            filterNodes = msgArray.drop(2),
                            connection = it,
                            count = true,
                        )
                    }
                }

                "REQ" -> {
                    connection?.let {
                        val subscriptionId = msgArray.get(1).asText()
                        subscribe(
                            subscriptionId = subscriptionId,
                            filterNodes = msgArray.drop(2),
                            connection = it,
                            count = false,
                        )
                    }
                }

                "AUTH" -> {
                    val event = Event.fromJson(msgArray.get(1).toString())
                    val exception = validateAuthEvent(event, connection?.authChallenge ?: "").exceptionOrNull()
                    if (exception != null) {
                        Log.d(Citrine.TAG, exception.message!!)
                        connection?.send(CommandResult.invalid(event, exception.message!!).toJson())
                        return
                    }

                    Log.d(Citrine.TAG, "AUTH successful ${event.toJson()}")
                    connection?.users?.add(event.pubKey)
                    connection?.send(CommandResult.ok(event).toJson())
                }

                "EVENT" -> {
                    val event = Event.fromJson(msgArray.get(1).toString())
                    processEvent(event, connection)
                }

                "CLOSE" -> {
                    EventSubscription.close(msgArray.get(1).asText())
                }

                "NEG-OPEN" -> {
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

                "NEG-MSG" -> {
                    connection?.let {
                        NegentropyHandler.handleMsg(
                            connection = it,
                            subId = msgArray.get(1).asText(),
                            msgHex = msgArray.get(2).asText(),
                            objectMapper = objectMapper,
                        )
                    }
                }

                "NEG-CLOSE" -> {
                    connection?.let {
                        NegentropyHandler.handleClose(it, msgArray.get(1).asText())
                    }
                }

                "PING" -> {
                    try {
                        connection?.send(NoticeResult("PONG").toJson())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(Citrine.TAG, "Failed to send pong response", e)
                    }
                }

                else -> {
                    try {
                        val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                        Log.d(Citrine.TAG, errorMessage)
                        connection?.send(errorMessage)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(Citrine.TAG, "Failed to send response", e)
                    }
                }
            }
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

    enum class VerificationResult {
        InvalidId,
        InvalidSignature,
        Expired,
        Valid,
        KindNotAllowed,
        KindRejected,
        PubkeyNotAllowed,
        TaggedPubkeyNotAllowed,
        Deleted,
        AlreadyInDatabase,
        TaggedPubKeyMismatch,
        NewestEventAlreadyInDatabase,
        AuthRequiredForProtectedEvent,
        ProtectedEventEmbeddedInRepost,
        RejectedByNip29,
    }

    suspend fun verifyEvent(event: Event, connection: Connection?, shouldVerify: Boolean, fromAggregator: Boolean = false): VerificationResult {
        if (!shouldVerify) {
            return VerificationResult.Valid
        }

        if (event.isExpired() && connection != null) {
            Log.d(Citrine.TAG, "event expired ${event.id} ${event.expiration()}")
            return VerificationResult.Expired
        }

        if (event.kind in Settings.rejectedKinds) {
            Log.d(Citrine.TAG, "kind rejected ${event.kind}")
            return VerificationResult.KindRejected
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            Log.d(Citrine.TAG, "kind not allowed ${event.kind}")
            return VerificationResult.KindNotAllowed
        }

        // Aggregator-fetched events bypass the author/tagged-pubkey allowlists
        // because the aggregator already validated them against the user's
        // requested subscription filter before handing them off here.
        if (!fromAggregator && (Settings.allowedTaggedPubKeys.isNotEmpty() || Settings.allowedPubKeys.isNotEmpty())) {
            val taggedUsers = event.taggedUsers()
            if (Settings.allowedTaggedPubKeys.isNotEmpty() && taggedUsers.isNotEmpty() && taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }) {
                if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                    Log.d(Citrine.TAG, "tagged pubkey not allowed ${event.id}")
                    return VerificationResult.TaggedPubkeyNotAllowed
                }
            }

            if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
                if (Settings.allowedTaggedPubKeys.isEmpty() || taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }) {
                    Log.d(Citrine.TAG, "pubkey not allowed ${event.id}")
                    return VerificationResult.PubkeyNotAllowed
                }
            }
        }

        if (event.isProtected() && connection != null) {
            val remoteHost = connection.session.call.request.local.remoteHost
            val isLocalHost = remoteHost in listOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

            if (!isLocalHost && !connection.users.contains(event.pubKey)) {
                Log.d(Citrine.TAG, "auth required for protected event ${event.id}")
                return VerificationResult.AuthRequiredForProtectedEvent
            }
        }

        // NIP-70: reposts of protected events MUST NOT embed the reposted event.
        if (event.kind == RepostEvent.KIND || event.kind == GenericRepostEvent.KIND) {
            val embedded = try {
                Event.fromJson(event.content)
            } catch (_: Exception) {
                null
            }
            if (embedded != null && embedded.isProtected()) {
                Log.d(Citrine.TAG, "rejecting repost ${event.id} that embeds protected event ${embedded.id}")
                return VerificationResult.ProtectedEventEmbeddedInRepost
            }
        }

        // Verify the signature before any DB access so invalid events are
        // rejected without paying for the reads below.
        if (!event.verify()) {
            Log.d(Citrine.TAG, "event ${event.id} does not have a valid id or signature")
            return VerificationResult.InvalidId
        }

        val deletedEvents = appDatabase.eventDao().getDeletedEvents(event.id)
        if (deletedEvents.isNotEmpty()) {
            Log.d(Citrine.TAG, "Event deleted ${event.id}")
            return VerificationResult.Deleted
        }

        // NIP-29: ids removed by a group admin's kind-9005 moderation event stay deleted,
        // but only for groups this relay manages — mirrored 9005s from foreign groups
        // must not block re-imports.
        if (GroupManager.hasGroups()) {
            val moderationDeleted = appDatabase.eventDao().getModerationDeletedGroups(event.id)
            if (moderationDeleted.any { GroupManager.get(it) != null }) {
                Log.d(Citrine.TAG, "Event deleted by group moderation ${event.id}")
                return VerificationResult.Deleted
            }
        }

        if (event is AddressableEvent) {
            val events = appDatabase.eventDao().getDeletedEventsByATag(event.address().toValue())
            events.forEach { deletedAt ->
                if (deletedAt >= event.createdAt) {
                    Log.d(Citrine.TAG, "Event deleted ${event.id}")
                    return VerificationResult.Deleted
                }
            }
        }

        when {
            event.shouldDelete() -> {
                val eventEntity = appDatabase.eventDao().getById(event.id)
                if (eventEntity != null) {
                    Log.d(Citrine.TAG, "Event already in database ${event.id}")
                    return VerificationResult.AlreadyInDatabase
                }
                event.taggedEvents().forEach { taggedEvent ->
                    val taggedEventEntity = appDatabase.eventDao().getById(taggedEvent.eventId)
                    if (taggedEventEntity != null && taggedEventEntity.event.pubkey != event.pubKey) {
                        Log.d(Citrine.TAG, "Tagged event pubkey mismatch ${event.id}")
                        return VerificationResult.TaggedPubKeyMismatch
                    }
                }
                event.taggedAddresses().forEach { aTag ->
                    val taggedEvents = EventRepository.query(
                        appDatabase,
                        EventFilter(
                            authors = setOf(aTag.pubKeyHex),
                            kinds = setOf(aTag.kind),
                            tags = mapOf("d" to setOf(aTag.dTag)),
                            until = event.createdAt.toInt(),
                        ),
                    )
                    taggedEvents.forEach {
                        if (it.event.pubkey != event.pubKey) {
                            Log.d(Citrine.TAG, "Tagged event pubkey mismatch ${event.id}")
                            return VerificationResult.TaggedPubKeyMismatch
                        }
                    }
                }
            }
            event.isParameterizedReplaceable() -> {
                val newest = appDatabase.eventDao().getNewestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "", event.createdAt)
                if (newest.isNotEmpty()) {
                    Log.d(Citrine.TAG, "newest event already in database ${event.id}")
                    return VerificationResult.NewestEventAlreadyInDatabase
                }
            }
            event.shouldOverwrite() -> {
                val newest = appDatabase.eventDao().getByKindNewest(event.kind, event.pubKey, event.createdAt)
                if (newest.isNotEmpty()) {
                    Log.d(Citrine.TAG, "newest event already in database ${event.id}")
                    return VerificationResult.NewestEventAlreadyInDatabase
                }
            }
            else -> {
                val eventEntity = appDatabase.eventDao().getById(event.id)
                if (eventEntity != null && !event.isEphemeral()) {
                    Log.d(Citrine.TAG, "Event already in database ${event.id}")
                    return VerificationResult.AlreadyInDatabase
                }
            }
        }
        return VerificationResult.Valid
    }

    suspend fun innerProcessEvent(event: Event, connection: Connection?, shouldVerify: Boolean = true, fromAggregator: Boolean = false): VerificationResult {
        val result = verifyEvent(event, connection, shouldVerify, fromAggregator)
        when (result) {
            VerificationResult.AuthRequiredForProtectedEvent -> {
                connection?.send(AuthResult.challenge(connection.authChallenge).toJson())
                connection?.send(CommandResult.required(event, "this event may only be published by its author").toJson())
            }
            VerificationResult.ProtectedEventEmbeddedInRepost -> {
                connection?.send(CommandResult.invalid(event, "blocked: reposts of protected events must not embed the reposted event").toJson())
            }
            VerificationResult.InvalidId -> {
                connection?.send(
                    CommandResult.invalid(
                        event,
                        "event id hash verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.InvalidSignature -> {
                connection?.send(
                    CommandResult.invalid(
                        event,
                        "event signature verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.Expired -> {
                connection?.send(CommandResult.invalid(event, "event expired").toJson())
            }
            VerificationResult.KindNotAllowed -> {
                connection?.send(CommandResult.invalid(event, "kind not allowed").toJson())
            }
            VerificationResult.KindRejected -> {
                connection?.send(CommandResult.blocked(event, "kind ${event.kind} is not accepted by this relay").toJson())
            }
            VerificationResult.PubkeyNotAllowed -> {
                connection?.send(CommandResult.invalid(event, "pubkey not allowed").toJson())
            }
            VerificationResult.TaggedPubkeyNotAllowed -> {
                connection?.send(CommandResult.invalid(event, "tagged pubkey not allowed").toJson())
            }
            VerificationResult.Deleted -> {
                connection?.send(CommandResult.invalid(event, "Event deleted").toJson())
            }
            VerificationResult.AlreadyInDatabase -> {
                connection?.send(CommandResult.duplicated(event).toJson())
            }
            VerificationResult.TaggedPubKeyMismatch -> {
                connection?.send(CommandResult.invalid(event, "Tagged event pubkey mismatch ${event.toJson()}").toJson())
            }
            VerificationResult.NewestEventAlreadyInDatabase -> {
                connection?.send(CommandResult.invalid(event, "newest event already in database").toJson())
            }
            VerificationResult.RejectedByNip29 -> {
                // verifyEvent never returns this; NIP-29 rejection happens below.
            }
            VerificationResult.Valid -> {
                var nip29PostSave: (suspend () -> Unit)? = null
                when (val nip29Result = Nip29Handler.process(event, connection, this, appDatabase)) {
                    is Nip29Result.Reject -> {
                        connection?.trySend(CommandResult(event.id, false, nip29Result.message).toJson())
                        return VerificationResult.RejectedByNip29
                    }
                    is Nip29Result.Accept -> nip29PostSave = nip29Result.postSave
                    Nip29Result.NotApplicable -> Unit
                }

                when {
                    event.shouldDelete() -> {
                        deleteEvent(event, connection)
                    }
                    event.isParameterizedReplaceable() -> {
                        handleParameterizedReplaceable(event, connection)
                    }
                    event.shouldOverwrite() -> {
                        override(event, connection)
                    }
                    else -> {
                        save(event, connection)
                    }
                }

                // if the event is ephemeral the response will be sent after the event is sent to subscriptions
                if (!event.isEphemeral()) {
                    connection?.send(CommandResult.ok(event).toJson())
                }

                // Membership changes and metadata regeneration run after the event is
                // stored and acknowledged.
                nip29PostSave?.let { callback ->
                    Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                        try {
                            callback()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(Citrine.TAG, "NIP-29 post-save action failed for ${event.id}", e)
                        }
                    }
                }
            }
        }
        return result
    }

    private suspend fun processEvent(event: Event, connection: Connection?) {
        innerProcessEvent(event, connection)
    }

    private fun dTagOrEmpty(event: Event): String = event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: ""

    private fun policyAllows(event: Event): Boolean {
        if (event.kind in Settings.rejectedKinds) {
            return false
        }
        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            return false
        }
        if (Settings.allowedTaggedPubKeys.isEmpty() && Settings.allowedPubKeys.isEmpty()) {
            return true
        }
        val taggedUsers = event.taggedUsers()
        if (Settings.allowedTaggedPubKeys.isNotEmpty() &&
            taggedUsers.isNotEmpty() &&
            taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }
        ) {
            if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                return false
            }
        }
        if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() ||
                taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Bulk-insert path used by the download-your-events flow.
     *
     * Differs from [innerProcessEvent] by:
     *  - verifying signatures in parallel on [Dispatchers.Default],
     *  - batching duplicate and kind-5 deletion checks into a small number of `IN (...)` queries,
     *  - collapsing in-batch replaceables to the newest per (pubkey, kind[, d-tag]),
     *  - writing all events and tags in one Room transaction,
     *  - skipping subscription fanout, offline-broadcast enqueue, and HistoryDatabase writes
     *    (all of which are irrelevant when importing the caller's own archive).
     */
    suspend fun innerProcessEventBatch(events: List<Event>) {
        if (events.isEmpty()) return

        val filtered = events
            .asSequence()
            .filter { !it.isExpired() }
            .filter { policyAllows(it) }
            .distinctBy { it.id }
            .toList()
        if (filtered.isEmpty()) return

        val verified = withContext(Dispatchers.Default) {
            val sem = Semaphore(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            filtered.map { e ->
                async { sem.withPermit { if (e.verify()) e else null } }
            }.awaitAll().filterNotNull()
        }
        if (verified.isEmpty()) return

        val ids = verified.map { it.id }
        val existing = ids.chunked(500)
            .flatMap { appDatabase.eventDao().existingIds(it) }
            .toHashSet()
        val deletedByE = ids.chunked(500)
            .flatMap { appDatabase.eventDao().getDeletedEventsByIds(it) }
            .toHashSet()

        val candidates = verified.filter {
            it.id !in existing && it.id !in deletedByE && !it.isEphemeral()
        }

        val addressableDeleted = HashSet<String>()
        candidates.forEach { e ->
            if (e is AddressableEvent) {
                val deletions = appDatabase.eventDao().getDeletedEventsByATag(e.address().toValue())
                if (deletions.any { deletedAt -> deletedAt >= e.createdAt }) {
                    addressableDeleted.add(e.id)
                }
            }
        }

        val accepted = candidates.filter { it.id !in addressableDeleted }

        val (replaceable, nonReplaceable) = accepted.partition {
            it.isParameterizedReplaceable() || it.shouldOverwrite()
        }
        val collapsedReplaceables = replaceable
            .groupBy { Triple(it.pubKey, it.kind, dTagOrEmpty(it)) }
            .values
            .map { group -> group.maxByOrNull { it.createdAt }!! }

        val (deletes, inserts) = nonReplaceable.partition { it.shouldDelete() }

        appDatabase.withTransaction {
            val finalReplaceables = collapsedReplaceables.filter { e ->
                if (e.isParameterizedReplaceable()) {
                    appDatabase.eventDao()
                        .getNewestReplaceable(e.kind, e.pubKey, dTagOrEmpty(e), e.createdAt)
                        .isEmpty()
                } else {
                    appDatabase.eventDao()
                        .getByKindNewest(e.kind, e.pubKey, e.createdAt)
                        .isEmpty()
                }
            }

            val toStore = inserts + finalReplaceables + deletes
            if (toStore.isNotEmpty()) {
                appDatabase.eventDao()
                    .insertEventsWithTagsBatch(toStore.map { it.toEventWithTags() })
            }

            finalReplaceables.forEach { e ->
                if (e.isParameterizedReplaceable()) {
                    val old = appDatabase.eventDao()
                        .getOldestReplaceable(e.kind, e.pubKey, dTagOrEmpty(e))
                    if (old.isNotEmpty()) appDatabase.eventDao().delete(old, e.pubKey)
                } else {
                    val old = appDatabase.eventDao().getByKind(e.kind, e.pubKey).drop(1)
                    if (old.isNotEmpty()) appDatabase.eventDao().delete(old, e.pubKey)
                }
            }

            deletes.forEach { applyDeleteTagsOwnedBy(it) }
        }
    }

    private suspend fun handleParameterizedReplaceable(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.instance).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection)
    }

    private suspend fun override(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(1)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.instance).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection)
    }

    @Volatile private var cachedIsOffline: Boolean = false

    @Volatile private var cachedIsOfflineAt: Long = 0L

    private fun isOffline(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cachedIsOfflineAt < 5_000L) return cachedIsOffline

        val connectivityManager = Citrine.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val offline = if (connectivityManager == null) {
            true
        } else {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        cachedIsOffline = offline
        cachedIsOfflineAt = now
        return offline
    }

    private fun broadcastWithWorkManager(dbEvent: EventWithTags) {
        try {
            val inputData = androidx.work.Data.Builder()
                .putString("event_id", dbEvent.event.id)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<EventBroadcastWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(Citrine.instance).enqueue(workRequest)
            Log.d(Citrine.TAG, "Queued event ${dbEvent.event.id} for broadcast when connectivity is restored")
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Failed to queue event for broadcast: ${e.message}", e)
        }
    }

    private suspend fun save(event: Event, connection: Connection?) {
        appDatabase.eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection)

        // Check if offline and broadcast with WorkManager
        if (isOffline()) {
            val dbEvent = appDatabase.eventDao().getById(event.id)
            if (dbEvent != null) {
                broadcastWithWorkManager(dbEvent)
            }
        }
    }

    private suspend fun deleteEvent(event: Event, connection: Connection?) {
        save(event, connection)
        applyDeleteTagsOwnedBy(event)
    }

    private suspend fun applyDeleteTagsOwnedBy(event: Event) {
        val taggedEventIds = event.taggedEvents().map { it.eventId }
        if (taggedEventIds.isNotEmpty()) {
            appDatabase.eventDao().delete(taggedEventIds, event.pubKey)
        }
        val taggedAddresses = event.taggedAddresses()
        if (taggedAddresses.isEmpty()) return

        taggedAddresses.forEach { aTag ->
            val events = EventRepository.query(
                appDatabase,
                EventFilter(
                    authors = setOf(aTag.pubKeyHex),
                    kinds = setOf(aTag.kind),
                    tags = mapOf("d" to setOf(aTag.dTag)),
                    until = event.createdAt.toInt(),
                ),
            )

            appDatabase.eventDao().delete(events.map { it.event.id }, event.pubKey)
        }
    }
    fun DocumentFile?.findFileRecursive(path: String): DocumentFile? {
        if (this == null) return null
        val parts = path.split("/")
        var current: DocumentFile = this
        for (part in parts) {
            val next = current.findFile(part) ?: return null
            current = next
        }
        return current
    }

    val resolver: ContentResolver = Citrine.instance.contentResolver

    private fun cursorToJson(cursor: Cursor?): String {
        if (cursor == null) {
            return "[]"
        }
        val result = mutableListOf<Map<String, Any?>>()
        val columnNames = cursor.columnNames
        while (cursor.moveToNext()) {
            val row = mutableMapOf<String, Any?>()
            for (columnName in columnNames) {
                val index = cursor.getColumnIndex(columnName)
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> row[columnName] = null
                    Cursor.FIELD_TYPE_INTEGER -> row[columnName] = cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> row[columnName] = cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> row[columnName] = cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> row[columnName] = cursor.getBlob(index)?.let { String(it) }
                }
            }
            result.add(row)
        }
        cursor.close()
        return objectMapper.writeValueAsString(result)
    }

    private suspend fun serveIndex(
        call: ApplicationCall,
        rootUri: Uri,
    ) {
        // Trim leading slash
        val requestedPath = call.request.uri.trimStart('/')
        Log.d(Citrine.TAG, requestedPath)

        val root = DocumentFile.fromTreeUri(Citrine.instance, rootUri)
        if (root == null) {
            Log.d(Citrine.TAG, "$requestedPath not found")
            return call.respond(HttpStatusCode.InternalServerError, null)
        }

        // First try requested file
        val targetFile = root.findFileRecursive(requestedPath)?.takeIf { it.exists() && it.isFile }
            // SPA fallback for root or route paths without file extension
            ?: if (requestedPath.isEmpty() || !requestedPath.contains('.')) {
                root.findFile("index.html")?.takeIf { it.exists() && it.isFile }
            } else {
                null
            }

        if (targetFile == null) {
            Log.d(Citrine.TAG, "$requestedPath not found")
            return call.respond(HttpStatusCode.NotFound, null)
        }

        resolver.openInputStream(targetFile.uri)?.use { input ->
            Log.d(Citrine.TAG, "${targetFile.name} sent")
            call.respondOutputStream(
                contentType = ContentType.defaultForFilePath(targetFile.name ?: "index.html"),
            ) {
                input.copyTo(this)
            }
        } ?: run {
            Log.d(Citrine.TAG, "${targetFile.name} not sent")
            call.respond(HttpStatusCode.InternalServerError, null)
        }
    }

    fun randomFreePort(): Int = ServerSocket(0).use { it.localPort }

    fun startWebClientServer(
        clientName: String,
        rootUri: Uri,
    ): WebClientServer {
        val port = randomFreePort()

        val server = embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = port,
        ) {
            routing {
                // Catch-all: /, /index.html, /assets/foo.js, etc
                get("{...}") {
                    serveIndex(call, rootUri)
                }
            }
        }.start(wait = false)

        return WebClientServer(
            name = clientName,
            rootUri = rootUri,
            port = port,
            server = server,
        )
    }

    /**
     * Serves a file from an app-internal [rootDir] (used by installed nsites). Mirrors
     * [serveIndex] but reads from a plain [File] tree instead of a SAF DocumentFile. Paths
     * are canonicalized and rejected if they escape [rootDir] (path-traversal guard). Falls
     * back to index.html for extension-less routes and to 404.html (with a 404 status) when
     * nothing matches, per NIP-5A.
     */
    private suspend fun serveFromDir(
        call: ApplicationCall,
        rootDir: File,
    ) {
        val requestedPath = call.request.uri.substringBefore('?').trimStart('/')
        val rootCanonical = rootDir.canonicalFile

        fun resolveSafe(path: String): File? {
            if (path.isEmpty()) return null
            val candidate = File(rootCanonical, path).canonicalFile
            return if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)) {
                candidate
            } else {
                null
            }
        }

        var target = resolveSafe(requestedPath)?.takeIf { it.isFile }
        // SPA/index fallback for the root or extension-less routes.
        if (target == null && (requestedPath.isEmpty() || !requestedPath.substringAfterLast('/').contains('.'))) {
            target = resolveSafe("index.html")?.takeIf { it.isFile }
        }
        // 404 fallback.
        if (target == null) {
            target = resolveSafe("404.html")?.takeIf { it.isFile }
        }

        if (target == null) {
            return call.respond(HttpStatusCode.NotFound, null)
        }

        val status = if (target.name == "404.html") HttpStatusCode.NotFound else HttpStatusCode.OK
        call.respondOutputStream(
            contentType = ContentType.defaultForFilePath(target.name),
            status = status,
        ) {
            target.inputStream().use { it.copyTo(this) }
        }
    }

    fun startNsiteServerFor(
        clientName: String,
        rootDir: File,
    ): WebClientServer {
        val port = randomFreePort()

        val server = embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = port,
        ) {
            routing {
                get("{...}") {
                    serveFromDir(call, rootDir)
                }
            }
        }.start(wait = false)

        return WebClientServer(
            name = clientName,
            rootUri = rootDir.toURI().toString().toUri(),
            port = port,
            server = server,
        )
    }

    /** Hot-adds (or replaces) a running server for an installed nsite without restarting the relay. */
    fun startNsiteServer(nsite: NsiteInfo) {
        val dir = File(Citrine.instance.filesDir, "nsites/${nsite.folderName}")
        if (!dir.isDirectory) return
        val key = "/${nsite.folderName}"
        webClientServers.remove(key)?.server?.stop()
        webClientServers[key] = startNsiteServerFor(nsite.folderName, dir)
        Log.d(Citrine.TAG, "Started nsite '${nsite.folderName}'")
    }

    /** Stops and removes a running nsite server (used on delete). */
    fun stopNsiteServer(folderName: String) {
        webClientServers.remove("/$folderName")?.server?.stop()
    }

    val proxyClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        expectSuccess = false
    }

    private fun extractClientName(host: String): String? {
        val cleanHost = host.substringBefore(":")
        if (!cleanHost.endsWith(".localhost")) return null
        return cleanHost.removeSuffix(".localhost")
            .takeIf { it.isNotBlank() }
    }

    private fun HttpMethod.allowsRequestBody(): Boolean = this == HttpMethod.Post ||
        this == HttpMethod.Put ||
        this == HttpMethod.Patch ||
        this == HttpMethod.Delete

    private val hopByHopHeaders = setOf(
        HttpHeaders.Connection.lowercase(),
        HttpHeaders.ProxyAuthenticate.lowercase(),
        HttpHeaders.ProxyAuthorization.lowercase(),
        HttpHeaders.Trailer.lowercase(),
        HttpHeaders.TransferEncoding.lowercase(),
        HttpHeaders.Upgrade.lowercase(),
    )

    private suspend fun proxyHttp(
        call: ApplicationCall,
        clientName: String,
    ) {
        val clientServer = webClientServers["/$clientName"]
            ?: return call.respond(HttpStatusCode.NotFound, null)

        val targetUrl = "http://127.0.0.1:${clientServer.port}${call.request.uri}"

        proxyClient.prepareRequest(targetUrl) {
            method = call.request.httpMethod

            // Forward headers to target, excluding engine-managed ones
            headers {
                call.request.headers.forEach { key, values ->
                    val lowerKey = key.lowercase()
                    if (!hopByHopHeaders.contains(lowerKey)) {
                        values.forEach { append(key, it) }
                    }
                }
                set(HttpHeaders.Host, "127.0.0.1:${clientServer.port}")
            }

            if (call.request.httpMethod.allowsRequestBody()) {
                setBody(call.receiveChannel())
            }
        }.execute { response ->
            // Filter headers before sending back to the original client
            response.headers.forEach { key, values ->
                val lowerKey = key.lowercase()
                // IMPORTANT: Let Ktor manage Transfer-Encoding and Content-Length
                if (!hopByHopHeaders.contains(lowerKey)) {
                    values.forEach { call.response.headers.append(key, it) }
                }
            }

            call.respondBytesWriter(
                contentType = response.contentType(),
                status = response.status,
            ) {
                // Streaming happens here
                response.bodyAsChannel().copyTo(this)
            }
        }
    }

    fun startWebClients() {
        Settings.webClients.forEach { (name, uriString) ->
            val uri = uriString.toUri()
            val clientServer = startWebClientServer(name, uri)
            webClientServers[name] = clientServer

            Log.d(
                Citrine.TAG,
                "Started web client '$name' on port ${clientServer.port}",
            )
        }

        Settings.nsites.forEach { nsite ->
            val dir = File(Citrine.instance.filesDir, "nsites/${nsite.folderName}")
            if (dir.isDirectory) {
                val key = "/${nsite.folderName}"
                val clientServer = startNsiteServerFor(nsite.folderName, dir)
                webClientServers[key] = clientServer

                Log.d(
                    Citrine.TAG,
                    "Started nsite '${nsite.folderName}' on port ${clientServer.port}",
                )
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startKtorHttpServer(host: String, port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        startWebClients()

        return embeddedServer(
            CIO,
            port = port,
            host = host,
        ) {
            install(WebSockets) {
                pingPeriodMillis = 5000L
                timeoutMillis = 300000L
                // permessage-deflate is intentionally NOT installed. Nearly all clients
                // connect over localhost/LAN where compression only burns CPU/battery,
                // and some client deflate implementations (e.g. gorilla/websocket's
                // flate reader) have a history of blocking mid-stream, which stalls
                // event delivery. Clients that don't negotiate the extension simply
                // receive uncompressed frames either way.
            }

            routing {
                get("{...}") {
                    val clientName = extractClientName(call.request.host())
                        ?: return@get call.respond(HttpStatusCode.NotFound, null)

                    if (!webClientServers.containsKey("/$clientName")) {
                        call.respond(HttpStatusCode.NotFound, null)
                        return@get
                    }

                    proxyHttp(call, clientName)
                }

                get("/") {
                    val clientName = extractClientName(call.request.host())

                    // If this is a client subdomain, proxy instead
                    if (clientName != null && webClientServers.containsKey("/$clientName")) {
                        proxyHttp(call, clientName)
                        return@get
                    }

                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Credentials", "true")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Expose-Headers", "*")

                    if (call.request.httpMethod == HttpMethod.Options) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
                    } else if (call.request.headers["Accept"] == "application/nostr+json") {
                        LocalPreferences.loadSettingsFromEncryptedStorage(Citrine.instance)

                        val supportedNips = mutableListOf(1, 2, 4, 9, 11, 29, 40, 42, 45, 50, 59, 65, 70, 77)

                        // NIP-29 clients discover the relay's group-signing key via the
                        // "self" field: the owner's key when configured, otherwise the
                        // relay's generated identity.
                        val relayPubkey = RelayIdentity.pubKeyHex()

                        call.respondText(
                            """
                            {
                              "name": "${Settings.name}",
                              "description": "${Settings.description}",
                              "pubkey": "${Settings.ownerPubkey.ifBlank { relayPubkey }}",
                              "contact": "${Settings.contact}",
                              "self": "$relayPubkey",
                              "supported_nips": $supportedNips,
                              "software": "https://github.com/greenart7c3/Citrine",
                              "version": "${BuildConfig.VERSION_NAME}",
                              "icon": "${Settings.relayIcon}"
                            }
                            """.trimIndent(),
                            ContentType.Application.Json,
                        )
                    } else {
                        call.respondText(
                            "Use a Nostr client or Websocket client to connect",
                            ContentType.Text.Html,
                        )
                    }
                }

                // ContentProvider test endpoints (for Postman testing)
                get("/api/events") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Content-Type", "application/json")

                    try {
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                        val createdAtFrom = call.request.queryParameters["createdAt_from"]?.toLongOrNull()
                        val createdAtTo = call.request.queryParameters["createdAt_to"]?.toLongOrNull()

                        val uriBuilder = "content://com.greenart7c3.citrine.provider/events".toUri()
                            .buildUpon()
                            .appendQueryParameter("limit", limit.toString())
                            .appendQueryParameter("offset", offset.toString())

                        createdAtFrom?.let { uriBuilder.appendQueryParameter("createdAt_from", it.toString()) }
                        createdAtTo?.let { uriBuilder.appendQueryParameter("createdAt_to", it.toString()) }

                        val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
                        val result = cursorToJson(cursor)

                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Exception) {
                        Log.e(Citrine.TAG, "Error querying events", e)
                        call.respondText(
                            """{"error": "${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }

                get("/api/events/by_pubkey") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Content-Type", "application/json")

                    try {
                        val pubkey = call.request.queryParameters["pubkey"]
                        if (pubkey == null) {
                            call.respondText(
                                """{"error": "pubkey parameter required"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest,
                            )
                            return@get
                        }

                        val kind = call.request.queryParameters["kind"]?.toIntOrNull()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                        val uriBuilder = "content://com.greenart7c3.citrine.provider/events/by_pubkey".toUri()
                            .buildUpon()
                            .appendQueryParameter("pubkey", pubkey)
                            .appendQueryParameter("limit", limit.toString())
                            .appendQueryParameter("offset", offset.toString())

                        kind?.let { uriBuilder.appendQueryParameter("kind", it.toString()) }

                        val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
                        val result = cursorToJson(cursor)

                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Exception) {
                        Log.e(Citrine.TAG, "Error querying events by pubkey", e)
                        call.respondText(
                            """{"error": "${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }

                get("/api/events/by_kind") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Content-Type", "application/json")

                    try {
                        val kind = call.request.queryParameters["kind"]?.toIntOrNull()
                        if (kind == null) {
                            call.respondText(
                                """{"error": "kind parameter required"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest,
                            )
                            return@get
                        }

                        val pubkey = call.request.queryParameters["pubkey"]
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                        val uriBuilder = "content://com.greenart7c3.citrine.provider/events/by_kind".toUri()
                            .buildUpon()
                            .appendQueryParameter("kind", kind.toString())
                            .appendQueryParameter("limit", limit.toString())
                            .appendQueryParameter("offset", offset.toString())

                        pubkey?.let { uriBuilder.appendQueryParameter("pubkey", it) }

                        val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
                        val result = cursorToJson(cursor)

                        call.respondText(result, ContentType.Application.Json)
                    } catch (e: Exception) {
                        Log.e(Citrine.TAG, "Error querying events by kind", e)
                        call.respondText(
                            """{"error": "${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }

                // HTTP endpoints for testing ContentProvider errors
                get("/api/test/errors/invalid_offset") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events?offset=-1".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                get("/api/test/errors/invalid_limit") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events?limit=-1".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                get("/api/test/errors/invalid_date_range") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events?createdAtFrom=1000&createdAtTo=500".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                get("/api/test/errors/missing_pubkey") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events/by_pubkey".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                get("/api/test/errors/missing_kind") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events/by_kind".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                get("/api/test/errors/invalid_event_id") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    val uri = "content://${CitrineContract.AUTHORITY}/events/invalid_event_id".toUri()
                    val cursor = resolver.query(uri, null, null, null, null)
                    call.respondText(cursorToJson(cursor), ContentType.Application.Json)
                }

                // WebSocket endpoint
                webSocket("/") {
                    val thisConnection = Connection(this)
                    connections.update { it + thisConnection }

                    Log.d(Citrine.TAG, "New connection from ${this.call.request.local.remoteHost} ${thisConnection.name}")

                    thisConnection.trySend(AuthResult(thisConnection.authChallenge).toJson())

                    // Decouple socket reads from message processing: parsing, signature
                    // verification, and DB checks are CPU/IO heavy and would otherwise run
                    // on the network engine's threads, stalling reads for this and other
                    // connections. A bounded channel keeps per-connection ordering while
                    // letting reads continue during processing bursts.
                    val messageChannel = Channel<String>(capacity = 256)
                    val processor = launch(Dispatchers.Default) {
                        for (newMessage in messageChannel) {
                            try {
                                processNewRelayMessage(newMessage, thisConnection)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.d(Citrine.TAG, "Error processing message: $e", e)
                                try {
                                    thisConnection.trySend(
                                        NoticeResult.invalid("Error processing message").toJson(),
                                    )
                                } catch (sendEx: Exception) {
                                    Log.d(Citrine.TAG, sendEx.toString(), sendEx)
                                }
                            }
                        }
                    }

                    try {
                        for (message in incoming) {
                            if (message !is Frame.Text) continue
                            messageChannel.send(message.readText())
                        }
                    } finally {
                        // Always clean up, even on cancellation
                        messageChannel.close()
                        withContext(NonCancellable) {
                            // Let already-received messages finish (stores events, sends OKs)
                            // before tearing the connection down.
                            withTimeoutOrNull(10_000) { processor.join() }
                            removeConnection(thisConnection)
                        }
                        Log.d(Citrine.TAG, "Connection closed from ${thisConnection.name}")
                    }
                }
            }
        }
    }

    suspend fun removeConnection(connection: Connection) {
        if (!connection.closed.compareAndSet(false, true)) return
        Log.d(Citrine.TAG, "Removing ${connection.name}!")
        connection.finalize()
        try {
            connection.session.cancel()
        } catch (_: Throwable) {
        }
        connections.update { list -> list.filterNot { it === connection } }
    }
}

data class WebClientServer(
    val name: String,
    val rootUri: Uri,
    val port: Int,
    val server: EmbeddedServer<*, *>,
)
