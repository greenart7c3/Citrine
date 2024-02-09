package com.greenart7c3.citrine

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEventWithTags
import com.vitorpamplona.quartz.events.Event
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.util.zip.Deflater

class CustomWebSocketServer(private val port: Int, private val context: Context) {
    private lateinit var server: ApplicationEngine
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun port(): Int {
        return server.environment.connectors.first().port
    }

    fun start() {
        server = startKtorHttpServer(port)
    }

    fun stop() {
        server.stop(1000)
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        session: DefaultWebSocketServerSession
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.fields().asSequence()
                .filter { it.key.startsWith("#") }
                .map { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }
                .toMap()

            val filter = objectMapper.treeToValue(jsonNode, EventFilter::class.java)

            filter.copy(tags = tags)
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, session, context, objectMapper)
    }

    private suspend fun processNewRelayMessage(newMessage: String, session: DefaultWebSocketServerSession) {
        Log.d("message", newMessage)
        val msgArray = Event.mapper.readTree(newMessage)
        when (val type = msgArray.get(0).asText()) {
            "REQ" -> {
                val subscriptionId = msgArray.get(1).asText()
                subscribe(subscriptionId, msgArray.drop(2), session)
            }
            "EVENT" -> {
                Log.d("EVENT", newMessage)
                processEvent(msgArray.get(1), session)
            }
            "CLOSE" -> {
                EventSubscription.close(msgArray.get(1).asText())
            }
            "PING" -> {
                session.send(NoticeResult("PONG").toJson())
            }
            else -> {
                val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                Log.d("message", errorMessage)
                session.send(errorMessage)
            }
        }
    }

    private suspend fun processEvent(eventNode: JsonNode, session: DefaultWebSocketServerSession) {
        val event = objectMapper.treeToValue(eventNode, Event::class.java)

        if (!event.hasVerifiedSignature()) {
            session.send(CommandResult.invalid(event, "event signature verification failed").toJson())
            return
        }

        if (event.isExpired()) {
            session.send(CommandResult.invalid(event, "event expired").toJson())
            return
        }

        when {
            event.shouldDelete() -> deleteEvent(event)
            event.isParameterizedReplaceable() -> handleParameterizedReplaceable(event)
            event.shouldOverwrite() -> override(event)
            !event.isEphemeral() -> {
                val eventEntity = AppDatabase.getDatabase(context).eventDao().getById(event.id)
                if (eventEntity != null) {
                    session.send(CommandResult.duplicated(event).toJson())
                    return
                }
                save(event)
            }
        }

        session.send(CommandResult.ok(event).toJson())
    }

    private fun handleParameterizedReplaceable(event: Event) {
        save(event)
        val ids = AppDatabase.getDatabase(context).eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        AppDatabase.getDatabase(context).eventDao().delete(ids)
    }

    private fun override(event: Event) {
        save(event)
        AppDatabase.getDatabase(context).eventDao().deleteOldestByKind(event.kind, event.pubKey)
    }

    private fun save(event: Event) {
        AppDatabase.getDatabase(context).eventDao().insertEventWithTags(event.toEventWithTags())
    }

    private fun deleteEvent(event: Event) {
        save(event)
        AppDatabase.getDatabase(context).eventDao().delete(event.taggedEvents())
    }

    private fun startKtorHttpServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, port = port) {
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
                        compressIfBiggerThan(bytes = 4 * 1024)
                    }
                }
            }

            routing {
                // Handle HTTP GET requests
                get("/") {
                    if (call.request.headers["Accept"] == "application/nostr+json") {
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
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val message = frame.readText()
                                    processNewRelayMessage(message, this)
                                }
                                else -> {
                                    Log.d("error", frame.toString())
                                    send(NoticeResult.invalid("Error processing message").toJson())
                                }
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        Log.d("error", e.toString())
                        send(NoticeResult.invalid("Error processing message").toJson())
                    }
                }
            }
        }.start(wait = false)
    }
}
