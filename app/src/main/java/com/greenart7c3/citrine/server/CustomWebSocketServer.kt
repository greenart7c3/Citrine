package com.greenart7c3.citrine.server

import android.content.Context
import android.widget.Toast
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.server.nip29.Nip29Handler
import com.greenart7c3.citrine.server.nip29.Nip29Result
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.utils.isEphemeral
import com.vitorpamplona.quartz.nip01Core.core.Event
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// With Connection's 2M-char outbound budget, 50 connections bound outbound buffers to ~200 MB.
private const val MAX_CONNECTIONS = 50
private const val MIN_MAX_FRAME_SIZE = 4L * 1024 * 1024

class CustomWebSocketServer(
    private val host: String,
    private val port: Int,
    private val appDatabase: AppDatabase,
    private val context: Context,
    private val appScope: CoroutineScope,
    onEphemeralEventStored: () -> Unit,
) {
    val connections = MutableStateFlow<List<Connection>>(emptyList())
    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val messageHandler = RelayMessageHandler(this, appDatabase, objectMapper)
    private val verifier = EventVerifier(appDatabase)
    private val eventStore = EventStore(appDatabase, context, onEphemeralEventStored)
    private val webClients = WebClientHosting(context)

    fun start() {
        val serverSocket = try {
            ServerSocket(Settings.port)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.toString(), e)
            Toast.makeText(context, "Port ${Settings.port} is already in use", Toast.LENGTH_LONG).show()
            null
        }
        if (serverSocket == null) return
        serverSocket.close()

        appScope.launch(Dispatchers.IO) {
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
            webClients.startWebClients()
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
            webClients.stopAll()
        }
    }

    /**
     * The outcome of checking an event. Each refusal carries the OK reply the client gets
     * ([refusal]); it is null for [Valid], for [RejectedByNip29] (answered by the NIP-29
     * handler) and for [AuthRequiredForProtectedEvent] (answered with an AUTH challenge too).
     */
    enum class VerificationResult(internal val refusal: ((Event) -> CommandResult)?) {
        InvalidId({ CommandResult.invalid(it, "event id hash verification failed") }),
        InvalidSignature({ CommandResult.invalid(it, "event signature verification failed") }),
        Expired({ CommandResult.invalid(it, "event expired") }),
        Valid(null),
        KindNotAllowed({ CommandResult.blocked(it, "kind not allowed") }),
        KindRejected({ CommandResult.blocked(it, "kind ${it.kind} is not accepted by this relay") }),
        PubkeyNotAllowed({ CommandResult.blocked(it, "pubkey not allowed") }),
        TaggedPubkeyNotAllowed({ CommandResult.blocked(it, "tagged pubkey not allowed") }),
        Deleted({ CommandResult(it.id, false, "deleted: event has been deleted by its author") }),
        AlreadyInDatabase({ CommandResult.duplicated(it) }),
        TaggedPubKeyMismatch({ CommandResult.invalid(it, "Tagged event pubkey mismatch ${it.toJson()}") }),
        NewestEventAlreadyInDatabase({ CommandResult(it.id, false, "duplicate: a newer event already exists for this replaceable") }),
        AuthRequiredForProtectedEvent(null),
        ProtectedEventEmbeddedInRepost({ CommandResult.invalid(it, "blocked: reposts of protected events must not embed the reposted event") }),
        RejectedByNip29(null),
        BannedPubkey({ CommandResult.invalid(it, "blocked: pubkey is banned") }),
        BannedEvent({ CommandResult.invalid(it, "blocked: event is banned") }),
        EventTooLarge({ CommandResult.invalid(it, "invalid: event too large (max ${Settings.maxEventSize} characters of content)") }),
    }

    suspend fun verifyEvent(event: Event, connection: Connection?, shouldVerify: Boolean, fromAggregator: Boolean = false): VerificationResult = verifier.verify(event, connection, shouldVerify, fromAggregator)

    suspend fun innerProcessEvent(event: Event, connection: Connection?, shouldVerify: Boolean = true, fromAggregator: Boolean = false): VerificationResult {
        val result = verifyEvent(event, connection, shouldVerify, fromAggregator)
        when (result) {
            VerificationResult.RejectedByNip29 -> {
                // verifyEvent never returns this; NIP-29 rejection happens below.
            }
            VerificationResult.Valid -> {
                if (!storeVerified(event, connection)) return VerificationResult.RejectedByNip29
            }
            else -> connection?.sendRefusal(event, result)
        }
        return result
    }

    private suspend fun storeVerified(event: Event, connection: Connection?): Boolean {
        var nip29PostSave: (suspend () -> Unit)? = null
        when (val nip29Result = Nip29Handler.process(event, this, appDatabase)) {
            is Nip29Result.Reject -> {
                connection?.trySend(CommandResult(event.id, false, nip29Result.message).toJson())
                return false
            }
            is Nip29Result.Accept -> nip29PostSave = nip29Result.postSave
            Nip29Result.NotApplicable -> Unit
        }

        eventStore.store(event, connection)

        // if the event is ephemeral the response will be sent after the event is sent to subscriptions
        if (!event.isEphemeral()) {
            connection?.send(CommandResult.ok(event).toJson())
        }

        // Membership changes and metadata regeneration run after the event is
        // stored and acknowledged.
        nip29PostSave?.let { callback ->
            appScope.launch(Dispatchers.IO) {
                try {
                    callback()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(Citrine.TAG, "NIP-29 post-save action failed for ${event.id}", e)
                }
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
        eventStore.storeBatch(events)
    }

    /** Hot-adds (or replaces) a running server for an installed nsite without restarting the relay. */
    fun startNsiteServer(nsite: NsiteInfo) {
        webClients.startNsiteServer(nsite)
    }

    /** Stops and removes a running nsite server (used on delete). */
    fun stopNsiteServer(folderName: String) {
        webClients.stopNsiteServer(folderName)
    }

    private fun startKtorHttpServer(host: String, port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        webClients.startWebClients()

        return embeddedServer(
            CIO,
            port = port,
            host = host,
        ) {
            installIpBlock()
            installRelayWebSockets()
            installRelayRoutes()
        }
    }

    private fun Application.installIpBlock() {
        // NIP-86 IP block enforcement — a single chokepoint that covers every
        // HTTP route and the WebSocket upgrade handshake.
        intercept(ApplicationCallPipeline.Plugins) {
            val ip = call.request.local.remoteHost
            if (Settings.blockedIps.containsKey(ip)) {
                call.respondText("ip blocked", status = HttpStatusCode.Forbidden)
                finish()
            }
        }
    }

    private fun Application.installRelayWebSockets() {
        install(WebSockets) {
            // 60s keepalive (down from 5s) cuts wakeups by 12x and lets cellular/Wi-Fi
            // radios enter idle sleep between pings while still detecting dead peers well
            // within NAT and Tor circuit timeouts.
            pingPeriodMillis = 60_000L
            timeoutMillis = 300_000L
            // Ktor's default is unlimited, so one frame could be read whole into the heap
            // before the event size check runs. 4x covers JSON escaping of max-size content.
            maxFrameSize = maxOf(MIN_MAX_FRAME_SIZE, Settings.maxEventSize * 4L)
            // Ktor's outgoing queue is unlimited by default, so session.send() never
            // suspended and a non-reading client pinned the heap behind Connection's budget.
            channels {
                outgoing = bounded(8, ChannelOverflow.SUSPEND)
            }
            // permessage-deflate is intentionally NOT installed. Nearly all clients
            // connect over localhost/LAN where compression only burns CPU/battery,
            // and some client deflate implementations (e.g. gorilla/websocket's
            // flate reader) have a history of blocking mid-stream, which stalls
            // event delivery. Clients that don't negotiate the extension simply
            // receive uncompressed frames either way.
        }
    }

    private fun Application.installRelayRoutes() {
        routing {
            get("{...}") {
                webClients.proxyOrNotFound(call)
            }

            get("/") {
                if (!webClients.proxiedClientSubdomain(call)) {
                    respondRelayRoot(call, context)
                }
            }

            contentProviderApiRoutes(context.contentResolver, objectMapper)

            // NIP-86 Relay Management API
            post("/") {
                Nip86Handler.handle(call, appDatabase, context)
            }

            // WebSocket endpoint
            webSocket("/") {
                serveRelaySession()
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveRelaySession() {
        // Tor hidden-service clients all arrive from 127.0.0.1, so blockedIps can't
        // limit them; cap the total so per-connection buffers can't add up to an OOM.
        if (connections.value.size >= MAX_CONNECTIONS) {
            Log.w(Citrine.TAG, "Refusing connection: $MAX_CONNECTIONS connections already open")
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
            return
        }
        val thisConnection = Connection(this, appScope)
        connections.update { it + thisConnection }

        Log.d(Citrine.TAG, "New connection from ${this.call.request.local.remoteHost} ${thisConnection.name}")

        thisConnection.trySend(AuthResult(thisConnection.authChallenge).toJson())

        // Decouple socket reads from message processing: parsing, signature
        // verification, and DB checks are CPU/IO heavy and would otherwise run
        // on the network engine's threads, stalling reads for this and other
        // connections. A bounded channel keeps per-connection ordering while
        // letting reads continue during processing bursts.
        // Small: with maxFrameSize at several MB, 256 queued frames per connection
        // could pin the heap on their own.
        val messageChannel = Channel<String>(capacity = 16)
        val processor = launchMessageProcessor(messageChannel, thisConnection)

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

    private fun DefaultWebSocketServerSession.launchMessageProcessor(messageChannel: Channel<String>, thisConnection: Connection): Job = launch(Dispatchers.Default) {
        for (newMessage in messageChannel) {
            try {
                messageHandler.process(newMessage, thisConnection)
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
