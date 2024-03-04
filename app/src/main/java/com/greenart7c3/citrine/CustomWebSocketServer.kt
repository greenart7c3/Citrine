package com.greenart7c3.citrine

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEventWithTags
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
import kotlinx.coroutines.DelicateCoroutinesApi
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.zip.Deflater

class CustomWebSocketServer(private val port: Int, private val appDatabase: AppDatabase) {
    var server: ApplicationEngine? = null
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun port(): Int? {
        return server?.environment?.connectors?.first()?.port
    }

    fun start() {
        if (server == null) {
            server = startKtorHttpServer(port)
        } else {
            server!!.start(false)
        }
    }

    fun stop() {
        server!!.stop(1000)
        server = null
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        connection: Connection
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.fields().asSequence()
                .filter { it.key.startsWith("#") }
                .map { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }
                .toMap()

            val filter = objectMapper.treeToValue(jsonNode, EventFilter::class.java)

            filter.copy(tags = tags)
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, connection, appDatabase, objectMapper)
    }

    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection) {
        Log.d("message", newMessage)
        val msgArray = Event.mapper.readTree(newMessage)
        when (val type = msgArray.get(0).asText()) {
            "REQ" -> {
                val subscriptionId = msgArray.get(1).asText()
                subscribe(subscriptionId, msgArray.drop(2), connection)
            }
            "EVENT" -> {
                Log.d("EVENT", newMessage)
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
                Log.d("message", errorMessage)
                connection.session.send(errorMessage)
            }
        }
    }

    private suspend fun processEvent(eventNode: JsonNode, connection: Connection) {
        val event = objectMapper.treeToValue(eventNode, Event::class.java)

        if (!event.hasVerifiedSignature()) {
            connection.session.send(CommandResult.invalid(event, "event signature verification failed").toJson())
            return
        }

        if (event.isExpired()) {
            connection.session.send(CommandResult.invalid(event, "event expired").toJson())
            return
        }

        when {
            event.shouldDelete() -> deleteEvent(event)
            event.isParameterizedReplaceable() -> handleParameterizedReplaceable(event)
            event.shouldOverwrite() -> override(event)
            !event.isEphemeral() -> {
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
        appDatabase.eventDao().delete(ids)
    }

    private fun override(event: Event) {
        save(event)
        val ids = appDatabase.eventDao().getByKind(event.kind).drop(5)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids)
    }

    private fun save(event: Event) {
        appDatabase.eventDao().insertEventWithTags(event.toEventWithTags())
    }

    private fun deleteEvent(event: Event) {
        save(event)
        appDatabase.eventDao().delete(event.taggedEvents())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startKtorHttpServer(port: Int): ApplicationEngine {
        return embeddedServer(
            CIO,
            port = port,
            host = "127.0.0.1"
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
                val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())

                // Handle HTTP GET requests
                get("/") {
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Credentials", "true")
                    call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "*")
                    call.response.headers.appendIfAbsent("Access-Control-Expose-Headers", "*")
                    if (call.request.httpMethod == HttpMethod.Options) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
                    } else if (call.request.headers["Accept"] == "application/nostr+json") {
                        val json = """
                        {
                            "id": "ws://localhost:7777",
                            "name": "Citrine",
                            "description": "A Nostr relay in you phone",
                            "pubkey": "",
                            "supported_nips": [1,2,4,9,11,50],
                            "software": "https://github.com/greenart7c3/Citrine",
                            "version": "${BuildConfig.VERSION_NAME}"
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
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val message = frame.readText()
                                    processNewRelayMessage(message, thisConnection)
                                }
                                else -> {
                                    Log.d("error", frame.toString())
                                    send(NoticeResult.invalid("Error processing message").toJson())
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Log.d("error", e.toString(), e)
                        try {
                            if (!thisConnection.session.outgoing.isClosedForSend) {
                                send(NoticeResult.invalid(closeReason.await()?.message ?: "").toJson())
                            }
                        } catch (e: Exception) {
                            Log.d("error", e.toString(), e)
                        }
                    } finally {
                        Log.d("connection", "Removing ${thisConnection.name}!")
                        thisConnection.finalize()
                        connections -= thisConnection
                    }
                }
            }
        }.start(wait = false)
    }
}
