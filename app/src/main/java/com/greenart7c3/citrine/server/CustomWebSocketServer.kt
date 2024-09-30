package com.greenart7c3.citrine.server

import android.util.Log
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
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
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
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import kotlinx.coroutines.DelicateCoroutinesApi

class CustomWebSocketServer(
    private val host: String,
    private val port: Int,
    private val appDatabase: AppDatabase,
) {
    val connections: MutableSet<Connection> = Collections.synchronizedSet(LinkedHashSet())
    var server: ApplicationEngine? = null
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun start() {
        if (server == null) {
            server = startKtorHttpServer(host, port)
        } else {
            server!!.start(false)
        }
    }

    suspend fun stop() {
        server!!.stop(1000)
        server = null
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        connection: Connection,
        count: Boolean = false,
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.fields().asSequence()
                .filter { it.key.startsWith("#") }
                .map { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }
                .toMap()

            val filter = objectMapper.treeToValue(jsonNode, EventFilter::class.java)

            filter.copy(tags = tags)
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, connection, appDatabase, objectMapper, count)
    }

    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection) {
        Log.d(Citrine.TAG, newMessage + " from ${connection.session.call.request.local.remoteHost} ${connection.session.call.request.headers["User-Agent"]}")
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
                processEvent(msgArray.get(1), connection)
            }
            "CLOSE" -> {
                EventSubscription.close(msgArray.get(1).asText())
            }
            "PING" -> {
                connection.session.send(NoticeResult("PONG").toJson())
            }
            else -> {
                val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                Log.d(Citrine.TAG, errorMessage)
                connection.session.send(errorMessage)
            }
        }
    }

    private suspend fun processEvent(eventNode: JsonNode, connection: Connection) {
        val event = objectMapper.treeToValue(eventNode, Event::class.java)

        if (!event.hasCorrectIDHash()) {
            connection.session.send(
                CommandResult.invalid(
                    event,
                    "event id hash verification failed",
                ).toJson(),
            )
            return
        }

        if (!event.hasVerifiedSignature()) {
            connection.session.send(
                CommandResult.invalid(
                    event,
                    "event signature verification failed",
                ).toJson(),
            )
            return
        }

        if (event.isExpired()) {
            connection.session.send(CommandResult.invalid(event, "event expired").toJson())
            return
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            connection.session.send(CommandResult.invalid(event, "kind not allowed").toJson())
            return
        }

        if (Settings.allowedTaggedPubKeys.isNotEmpty() && event.taggedUsers().isNotEmpty() && event.taggedUsers().none { it in Settings.allowedTaggedPubKeys }) {
            if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                connection.session.send(CommandResult.invalid(event, "tagged pubkey not allowed").toJson())
                return
            }
        }

        if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() || event.taggedUsers().none { it in Settings.allowedTaggedPubKeys }) {
                connection.session.send(CommandResult.invalid(event, "pubkey not allowed").toJson())
                return
            }
        }

        when {
            event.shouldDelete() -> deleteEvent(event)
            event.isParameterizedReplaceable() -> handleParameterizedReplaceable(event)
            event.shouldOverwrite() -> override(event)
            else -> {
                val eventEntity = appDatabase.eventDao().getById(event.id)
                if (eventEntity != null) {
                    connection.session.send(CommandResult.duplicated(event).toJson())
                    return
                }
                save(event)
            }
        }

        connection.session.send(CommandResult.ok(event).toJson())
    }

    private fun handleParameterizedReplaceable(event: Event) {
        save(event)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
    }

    private fun override(event: Event) {
        save(event)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(5)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
    }

    private fun save(event: Event) {
        appDatabase.eventDao().insertEventWithTags(event.toEventWithTags())
    }

    private fun deleteEvent(event: Event) {
        save(event)
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
    private fun startKtorHttpServer(host: String, port: Int): ApplicationEngine {
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
                    connections += thisConnection
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
                        connections -= thisConnection
                    }
                }
            }
        }.start(wait = false)
    }
}
