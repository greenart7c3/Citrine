package com.greenart7c3.citrine.server

import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.utils.isParameterizedReplaceable
import com.greenart7c3.citrine.utils.shouldDelete
import com.greenart7c3.citrine.utils.shouldOverwrite
import com.vitorpamplona.quartz.events.Event
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import io.ktor.websocket.send
import java.net.ServerSocket
import java.util.Collections
import java.util.zip.Deflater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow

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
            if ((filter.since == null || filter.since == 0) && (filter.until == null || filter.until == 0) && filter.limit <= 0) {
                Log.d(Citrine.TAG, "No filter provided, setting limit to 1_000")
                limit = 1_000
            }
            filter.copy(
                tags = tags,
                limit = limit,
            )
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, connection, appDatabase, objectMapper, count)
    }

    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection?) {
        Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
        val msgArray = Event.mapper.readTree(newMessage)
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
                val event = Event.fromJson(msgArray.get(1))
                processEvent(event, connection)
            }
            "CLOSE" -> {
                EventSubscription.close(msgArray.get(1).asText())
            }
            "PING" -> {
                connection?.session?.send(NoticeResult("PONG").toJson())
            }
            else -> {
                val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                Log.d(Citrine.TAG, errorMessage)
                connection?.session?.send(errorMessage)
            }
        }
    }

    suspend fun innerProcessEvent(event: Event, connection: Connection?) {
        if (!event.hasCorrectIDHash()) {
            Log.d(Citrine.TAG, "event id hash verification failed ${event.toJson()}")
            connection?.session?.send(
                CommandResult.invalid(
                    event,
                    "event id hash verification failed",
                ).toJson(),
            )
            return
        }

        if (!event.hasVerifiedSignature()) {
            Log.d(Citrine.TAG, "event signature verification failed ${event.toJson()}")
            connection?.session?.send(
                CommandResult.invalid(
                    event,
                    "event signature verification failed",
                ).toJson(),
            )
            return
        }

        if (event.isExpired()) {
            Log.d(Citrine.TAG, "event expired ${event.toJson()}")
            connection?.session?.send(CommandResult.invalid(event, "event expired").toJson())
            return
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            Log.d(Citrine.TAG, "kind not allowed ${event.toJson()}")
            connection?.session?.send(CommandResult.invalid(event, "kind not allowed").toJson())
            return
        }

        if (Settings.allowedTaggedPubKeys.isNotEmpty() && event.taggedUsers().isNotEmpty() && event.taggedUsers().none { it in Settings.allowedTaggedPubKeys }) {
            if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                Log.d(Citrine.TAG, "tagged pubkey not allowed ${event.toJson()}")
                connection?.session?.send(CommandResult.invalid(event, "tagged pubkey not allowed").toJson())
                return
            }
        }

        if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() || event.taggedUsers().none { it in Settings.allowedTaggedPubKeys }) {
                Log.d(Citrine.TAG, "pubkey not allowed ${event.toJson()}")
                connection?.session?.send(CommandResult.invalid(event, "pubkey not allowed").toJson())
                return
            }
        }

        when {
            event.shouldDelete() -> {
                val eventEntity = appDatabase.eventDao().getById(event.id)
                if (eventEntity != null) {
                    Log.d(Citrine.TAG, "Event already in database ${event.toJson()}")
                    connection?.session?.send(CommandResult.duplicated(event).toJson())
                    return
                }
                deleteEvent(event, connection)
            }
            event.isParameterizedReplaceable() -> {
                val newest = appDatabase.eventDao().getNewestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "", event.createdAt)
                if (newest.isNotEmpty()) {
                    Log.d(Citrine.TAG, "newest event already in database ${event.toJson()}")
                    connection?.session?.send(CommandResult.invalid(event, "newest event already in database").toJson())
                    return
                }
                handleParameterizedReplaceable(event, connection)
            }
            event.shouldOverwrite() -> {
                val newest = appDatabase.eventDao().getByKindNewest(event.kind, event.pubKey, event.createdAt)
                if (newest.isNotEmpty()) {
                    Log.d(Citrine.TAG, "newest event already in database ${event.toJson()}")
                    connection?.session?.send(CommandResult.invalid(event, "newest event already in database").toJson())
                    return
                }
                override(event, connection)
            }
            else -> {
                val eventEntity = appDatabase.eventDao().getById(event.id)
                if (eventEntity != null) {
                    Log.d(Citrine.TAG, "Event already in database ${event.toJson()}")
                    connection?.session?.send(CommandResult.duplicated(event).toJson())
                    return
                }
                save(event, connection)
            }
        }

        connection?.session?.send(CommandResult.ok(event).toJson())
    }

    private suspend fun processEvent(event: Event, connection: Connection?) {
        innerProcessEvent(event, connection)
    }

    private suspend fun handleParameterizedReplaceable(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
    }

    private suspend fun override(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(1)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
    }

    private suspend fun save(event: Event, connection: Connection?) {
        Log.d(Citrine.TAG, "Saving event ${event.toJson()}")
        appDatabase.eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection)
    }

    private suspend fun deleteEvent(event: Event, connection: Connection?) {
        save(event, connection)
        appDatabase.eventDao().delete(event.taggedEvents(), event.pubKey)
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
            install(WebSockets) {
                pingPeriodMillis = 1000L
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
                            "supported_nips": [1,2,4,9,11,40,45,50,59],
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
                                        val message = frame.readText()
                                        processNewRelayMessage(message, thisConnection)
                                    }

                                    else -> {
                                        Log.d(Citrine.TAG, frame.toString())
                                        send(NoticeResult.invalid("Error processing message").toJson())
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.d(Citrine.TAG, e.toString(), e)
                                try {
                                    if (!thisConnection.session.outgoing.isClosedForSend) {
                                        send(NoticeResult.invalid("Error processing message").toJson())
                                    }
                                } catch (e: Exception) {
                                    Log.d(Citrine.TAG, e.toString(), e)
                                }
                            }
                        }
                    } finally {
                        Log.d(Citrine.TAG, "Removing ${thisConnection.name}!")
                        thisConnection.finalize()
                        connections.emit(connections.value - thisConnection)
                    }
                }
            }
        }.start(wait = false)
    }
}
