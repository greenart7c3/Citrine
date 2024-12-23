package com.greenart7c3.citrine.server

import android.util.Log
import android.widget.Toast
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.service.LocalPreferences
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
import rust.nostr.sdk.ClientMessage
import rust.nostr.sdk.ClientMessageEnum
import rust.nostr.sdk.Filter
import rust.nostr.sdk.RejectedReason
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.SaveEventStatus

class CustomWebSocketServer(
    private val host: String,
    private val port: Int,
) {
    val connections = MutableStateFlow(Collections.synchronizedList<Connection>(mutableListOf()))
    var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

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
        filters: List<Filter>,
        connection: Connection?,
        count: Boolean = false,
    ) {
        EventSubscription.subscribe(subscriptionId, filters, connection, count)
    }

    private suspend fun processNewRelayMessage(newMessage: String, connection: Connection?) {
        Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
        when (val type = ClientMessage.fromJson(newMessage).asEnum()) {
            is ClientMessageEnum.Count -> {
                subscribe(type.subscriptionId, type.filters, connection, true)
            }
            is ClientMessageEnum.Req -> {
                subscribe(type.subscriptionId, type.filters, connection)
            }
            is ClientMessageEnum.EventMsg -> {
                processEvent(type.event, connection)
            }
            is ClientMessageEnum.Close -> {
                EventSubscription.close(type.subscriptionId)
            }
            else -> {
                val errorMessage = RelayMessage.notice("unknown message type $type").asJson()
                Log.d(Citrine.TAG, errorMessage)
                connection?.session?.send(errorMessage)
            }
        }
    }

    suspend fun innerProcessEvent(event: rust.nostr.sdk.Event, connection: Connection?) {
        if (!event.verifySignature()) {
            // Log.d(Citrine.TAG, "event signature verification failed ${event.asJson()}")
            connection?.session?.send(
                RelayMessage.ok(event.id(), false, "event signature verification failed").asJson(),
            )
            return
        }

        if (event.kind().isEphemeral()) {
            when (val saved = AppDatabase.getNostrEphemeralDatabase().saveEvent(event)) {
                is SaveEventStatus.Rejected -> {
                    val reason = saved.v1
                    when (reason) {
                        RejectedReason.EPHEMERAL -> {
                            Log.d(Citrine.TAG, "Event rejected ephemeral ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "ephemeral").asJson())
                        }
                        RejectedReason.DUPLICATE -> {
                            Log.d(Citrine.TAG, "Event rejected duplicated ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), true, "duplicate:").asJson())
                        }
                        RejectedReason.DELETED -> {
                            Log.d(Citrine.TAG, "Event rejected deleted ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "deleted").asJson())
                        }
                        RejectedReason.EXPIRED -> {
                            Log.d(Citrine.TAG, "Event rejected expired ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "expired").asJson())
                        }
                        RejectedReason.REPLACED -> {
                            Log.d(Citrine.TAG, "Event rejected replaced ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "replaced").asJson())
                        }
                        RejectedReason.INVALID_DELETE -> {
                            Log.d(Citrine.TAG, "Event rejected invalid delete ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "invalid delete").asJson())
                        }
                        RejectedReason.OTHER -> {
                            Log.d(Citrine.TAG, "Event rejected other ${event.asJson()}")
                            connection?.session?.send(RelayMessage.ok(event.id(), false, "other").asJson())
                        }
                    }
                }

                SaveEventStatus.Success -> {
                    Log.d(Citrine.TAG, "Event saved ${event.asJson()}")
                    connection?.session?.send(RelayMessage.ok(event.id(), true, "").asJson())
                    EventSubscription.executeAll(event, connection)
                }
            }
            return
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind().asU16().toInt() !in Settings.allowedKinds) {
            // Log.d(Citrine.TAG, "kind not allowed ${event.asJson()}")
            connection?.session?.send(RelayMessage.ok(event.id(), false, "kind not allowed").asJson())
            return
        }

        if (Settings.allowedTaggedPubKeys.isNotEmpty() && event.tags().publicKeys().isNotEmpty() && event.tags().publicKeys().none { it.toHex() in Settings.allowedTaggedPubKeys }) {
            if (Settings.allowedPubKeys.isEmpty() || (event.author().toHex() !in Settings.allowedPubKeys)) {
                // Log.d(Citrine.TAG, "tagged pubkey not allowed ${event.asJson()}")
                connection?.session?.send(RelayMessage.ok(event.id(), false, "tagged pubkey not allowed").asJson())
                return
            }
        }

        if (Settings.allowedPubKeys.isNotEmpty() && event.author().toHex() !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() || event.tags().publicKeys().none { it.toHex() in Settings.allowedTaggedPubKeys }) {
                Log.d(Citrine.TAG, "pubkey not allowed ${event.asJson()}")
                connection?.session?.send(RelayMessage.ok(event.id(), false, "pubkey not allowed").asJson())
                return
            }
        }

        when (val saved = AppDatabase.getNostrDatabase().saveEvent(event)) {
            is SaveEventStatus.Rejected -> {
                val reason = saved.v1
                when (reason) {
                    RejectedReason.EPHEMERAL -> {
                        Log.d(Citrine.TAG, "Event rejected ephemeral ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "ephemeral").asJson())
                    }
                    RejectedReason.DUPLICATE -> {
                        Log.d(Citrine.TAG, "Event rejected duplicated ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), true, "duplicate:").asJson())
                    }
                    RejectedReason.DELETED -> {
                        Log.d(Citrine.TAG, "Event rejected deleted ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "deleted").asJson())
                    }
                    RejectedReason.EXPIRED -> {
                        Log.d(Citrine.TAG, "Event rejected expired ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "expired").asJson())
                    }
                    RejectedReason.REPLACED -> {
                        Log.d(Citrine.TAG, "Event rejected replaced ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "replaced").asJson())
                    }
                    RejectedReason.INVALID_DELETE -> {
                        Log.d(Citrine.TAG, "Event rejected invalid delete ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "invalid delete").asJson())
                    }
                    RejectedReason.OTHER -> {
                        Log.d(Citrine.TAG, "Event rejected other ${event.asJson()}")
                        connection?.session?.send(RelayMessage.ok(event.id(), false, "other").asJson())
                    }
                }
            }

            SaveEventStatus.Success -> {
                Log.d(Citrine.TAG, "Event saved ${event.asJson()}")
                connection?.session?.send(RelayMessage.ok(event.id(), true, "").asJson())
                EventSubscription.executeAll(event, connection)
            }
        }
    }

    private suspend fun processEvent(event: rust.nostr.sdk.Event, connection: Connection?) {
        innerProcessEvent(event, connection)
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
                                        send(RelayMessage.notice("Error processing message").asJson())
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.d(Citrine.TAG, e.toString(), e)
                                try {
                                    if (!thisConnection.session.outgoing.isClosedForSend) {
                                        send(RelayMessage.notice("Error processing message").asJson())
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
