package com.greenart7c3.citrine.server

import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.HistoryDatabase
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.utils.isParameterizedReplaceable
import com.greenart7c3.citrine.utils.shouldDelete
import com.greenart7c3.citrine.utils.shouldOverwrite
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.jackson.EventMapper
import com.vitorpamplona.quartz.nip01Core.tags.addressables.taggedAddresses
import com.vitorpamplona.quartz.nip01Core.tags.events.taggedEvents
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip01Core.verify
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.readText
import java.net.ServerSocket
import java.util.Collections
import java.util.zip.Deflater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CustomWebSocketServer(
    private val host: String,
    private val port: Int,
    private val appDatabase: AppDatabase,
) {
    val connections = MutableStateFlow(Collections.synchronizedList<Connection>(mutableListOf()))
    var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun start() {
        val serverSocket = try {
            ServerSocket(Settings.port)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.toString(), e)
            Toast.makeText(Citrine.getInstance(), "Port ${Settings.port} is already in use", Toast.LENGTH_LONG).show()
            null
        }
        if (serverSocket == null) return
        serverSocket.close()

        Log.d(Citrine.TAG, "Starting server on $host:$port isNull: ${server == null}")
        if (server == null) {
            server = startKtorHttpServer(host, port)
        } else {
            server!!.start(false)
        }
    }

    suspend fun stop() {
        Log.d(Citrine.TAG, "Stopping server")
        server?.stop(1000)
        server = null
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        connection: Connection?,
        count: Boolean = false,
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.fields().asSequence()
                .filter { it.key.startsWith("#") }
                .map { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }
                .toMap()

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

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection?) {
        try {
            Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
            val msgArray = EventMapper.mapper.readTree(newMessage)
            when (val type = msgArray.get(0).asText()) {
                "COUNT" -> {
                    val subscriptionId = msgArray.get(1).asText()
                    subscribe(subscriptionId, msgArray.drop(2), connection, true)
                }

                "REQ" -> {
                    val subscriptionId = msgArray.get(1).asText()
                    subscribe(subscriptionId, msgArray.drop(2), connection)
                }

                "EVENT" -> {
                    val event = Event.fromJson(msgArray.get(1).toString())
                    processEvent(event, connection)
                }

                "CLOSE" -> {
                    EventSubscription.close(msgArray.get(1).asText())
                }

                "PING" -> {
                    try {
                        connection?.session?.trySend(NoticeResult("PONG").toJson())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(Citrine.TAG, "Failed to send pong response", e)
                    }
                }

                else -> {
                    try {
                        val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                        Log.d(Citrine.TAG, errorMessage)
                        connection?.session?.trySend(errorMessage)
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
                connection?.session?.trySend(NoticeResult.invalid("Error processing message").toJson())
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
        PubkeyNotAllowed,
        TaggedPubkeyNotAllowed,
        Deleted,
        AlreadyInDatabase,
        TaggedPubKeyMismatch,
        NewestEventAlreadyInDatabase,
    }

    suspend fun verifyEvent(event: Event, shouldVerify: Boolean): VerificationResult {
        if (!shouldVerify) {
            return VerificationResult.Valid
        }

        if (event.isExpired()) {
            Log.d(Citrine.TAG, "event expired ${event.id} ${event.expiration()}")
            return VerificationResult.Expired
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            Log.d(Citrine.TAG, "kind not allowed ${event.kind}")
            return VerificationResult.KindNotAllowed
        }

        if (Settings.allowedTaggedPubKeys.isNotEmpty() && event.taggedUsers().isNotEmpty() && event.taggedUsers().none { it.pubKey in Settings.allowedTaggedPubKeys }) {
            if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                Log.d(Citrine.TAG, "tagged pubkey not allowed ${event.id}")
                return VerificationResult.TaggedPubkeyNotAllowed
            }
        }

        if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() || event.taggedUsers().none { it.pubKey in Settings.allowedTaggedPubKeys }) {
                Log.d(Citrine.TAG, "pubkey not allowed ${event.id}")
                return VerificationResult.PubkeyNotAllowed
            }
        }

        val deletedEvents = appDatabase.eventDao().getDeletedEvents(event.id)
        if (deletedEvents.isNotEmpty()) {
            Log.d(Citrine.TAG, "Event deleted ${event.id}")
            return VerificationResult.Deleted
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
                if (eventEntity != null) {
                    Log.d(Citrine.TAG, "Event already in database ${event.id}")
                    return VerificationResult.AlreadyInDatabase
                }
            }
        }
        if (!event.verify()) {
            Log.d(Citrine.TAG, "event ${event.id} does not have a valid id or signature")
            return VerificationResult.InvalidId
        }
        return VerificationResult.Valid
    }

    suspend fun innerProcessEvent(event: Event, connection: Connection?, shouldVerify: Boolean = true) {
        when (verifyEvent(event, shouldVerify)) {
            VerificationResult.InvalidId -> {
                connection?.session?.trySend(
                    CommandResult.invalid(
                        event,
                        "event id hash verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.InvalidSignature -> {
                connection?.session?.trySend(
                    CommandResult.invalid(
                        event,
                        "event signature verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.Expired -> {
                connection?.session?.trySend(CommandResult.invalid(event, "event expired").toJson())
            }
            VerificationResult.KindNotAllowed -> {
                connection?.session?.trySend(CommandResult.invalid(event, "kind not allowed").toJson())
            }
            VerificationResult.PubkeyNotAllowed -> {
                connection?.session?.trySend(CommandResult.invalid(event, "pubkey not allowed").toJson())
            }
            VerificationResult.TaggedPubkeyNotAllowed -> {
                connection?.session?.trySend(CommandResult.invalid(event, "tagged pubkey not allowed").toJson())
            }
            VerificationResult.Deleted -> {
                connection?.session?.trySend(CommandResult.invalid(event, "Event deleted").toJson())
            }
            VerificationResult.AlreadyInDatabase -> {
                connection?.session?.trySend(CommandResult.duplicated(event).toJson())
            }
            VerificationResult.TaggedPubKeyMismatch -> {
                connection?.session?.trySend(CommandResult.invalid(event, "Tagged event pubkey mismatch ${event.toJson()}").toJson())
            }
            VerificationResult.NewestEventAlreadyInDatabase -> {
                connection?.session?.trySend(CommandResult.invalid(event, "newest event already in database").toJson())
            }
            VerificationResult.Valid -> {
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

                connection?.session?.trySend(CommandResult.ok(event).toJson())
            }
        }
    }

    private suspend fun processEvent(event: Event, connection: Connection?) {
        innerProcessEvent(event, connection)
    }

    private suspend fun handleParameterizedReplaceable(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.getInstance()).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection, sendEventToSubscriptions = false)
    }

    private suspend fun override(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(1)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.getInstance()).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection, sendEventToSubscriptions = false)
    }

    private suspend fun save(event: Event, connection: Connection?) {
        appDatabase.eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection)
    }

    private suspend fun deleteEvent(event: Event, connection: Connection?) {
        save(event, connection)
        appDatabase.eventDao().delete(event.taggedEvents().map { it.eventId }, event.pubKey)
        val taggedAddresses = event.taggedAddresses()
        if (taggedAddresses.isEmpty()) return

        event.taggedAddresses().forEach { aTag ->
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

    @OptIn(DelicateCoroutinesApi::class)
    private fun startKtorHttpServer(host: String, port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(
            CIO,
            port = port,
            host = host,
        ) {
            monitor.subscribe(ApplicationStarted) {
                Log.d(Citrine.TAG, "Server started on $host:$port")
                CustomWebSocketService.hasStarted = true
            }

            monitor.subscribe(ApplicationStopped) {
                Log.d(Citrine.TAG, "Server stopped")
                CustomWebSocketService.hasStarted = false
            }

            install(WebSockets) {
                pingPeriodMillis = 5000L
                timeoutMillis = 300000L
                extensions {
                    install(WebSocketDeflateExtension) {
                        /**
                         * Compression level to use for [java.util.zip.Deflater].
                         */
                        compressionLevel = Deflater.DEFAULT_COMPRESSION

                        /**
                         * Prevent compressing small outgoing frames.
                         */
                        compressIfBiggerThan(bytes = 0)
                    }
                }
            }

            routing {
                // Handle HTTP GET requests
                get("/") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Credentials", "true")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Expose-Headers", "*")
                    if (call.request.httpMethod == HttpMethod.Options) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
                    } else if (call.request.headers["Accept"] == "application/nostr+json") {
                        LocalPreferences.loadSettingsFromEncryptedStorage(Citrine.getInstance())
                        val json = """
                        {
                            "name": "${Settings.name}",
                            "description": "${Settings.description}",
                            "pubkey": "${Settings.ownerPubkey}",
                            "contact": "${Settings.contact}",
                            "supported_nips": [1,2,4,9,11,40,45,50,59,65],
                            "software": "https://github.com/greenart7c3/Citrine",
                            "version": "${BuildConfig.VERSION_NAME}",
                            "icon": "${Settings.relayIcon}"
                        }
                        """
                        call.respondText(json, ContentType.Application.Json)
                    } else {
                        call.respondText("Use a Nostr client or Websocket client to connect", ContentType.Text.Html)
                    }
                }

                // WebSocket endpoint
                webSocket("/") {
                    val thisConnection = Connection(this)
                    connections.emit(connections.value + thisConnection)
                    Log.d(Citrine.TAG, "New connection from ${this.call.request.local.remoteHost} ${thisConnection.name}")
                    try {
                        for (frame in incoming) {
                            try {
                                when (frame) {
                                    is Frame.Text -> {
                                        Citrine.getInstance().applicationScope.launch {
                                            val message = frame.readText()
                                            processNewRelayMessage(message, thisConnection)
                                        }
                                    }

                                    else -> {
                                        Citrine.getInstance().applicationScope.launch {
                                            Log.d(Citrine.TAG, frame.toString())
                                            trySend(NoticeResult.invalid("Error processing message").toJson())
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.d(Citrine.TAG, e.toString(), e)
                                Citrine.getInstance().applicationScope.launch {
                                    try {
                                        trySend(NoticeResult.invalid("Error processing message").toJson())
                                    } catch (e: Exception) {
                                        Log.d(Citrine.TAG, e.toString(), e)
                                    }
                                }
                            }
                        }
                    } finally {
                        Log.d(Citrine.TAG, "Removing ${thisConnection.name}!")
                        thisConnection.finalize()
                        connections.value.remove(thisConnection)
                        connections.emit(connections.value)
                    }
                }
            }
        }.start(wait = false)
    }
}
