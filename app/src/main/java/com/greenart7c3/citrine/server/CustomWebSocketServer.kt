package com.greenart7c3.citrine.server

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
import com.greenart7c3.citrine.provider.CitrineContract
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.EventBroadcastWorker
import com.greenart7c3.citrine.service.LocalPreferences
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
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip70ProtectedEvts.isProtected
import com.vitorpamplona.quartz.utils.TimeUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
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
import io.ktor.server.request.receiveStream
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
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater

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
            Toast.makeText(Citrine.instance, "Port ${Settings.port} is already in use", Toast.LENGTH_LONG).show()
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

    fun stop() {
        Log.d(Citrine.TAG, "Stopping server")
        server?.stop(1000)
        server = null
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        connection: Connection,
        count: Boolean = false,
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.properties().asSequence()
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
            Log.d(Citrine.TAG, newMessage + " from ${connection?.session?.call?.request?.local?.remoteHost} ${connection?.session?.call?.request?.headers?.get("User-Agent")}")
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
                        connection?.trySend(CommandResult.invalid(event, exception.message!!).toJson())
                        return
                    }

                    Log.d(Citrine.TAG, "AUTH successful ${event.toJson()}")
                    connection?.users?.add(event.pubKey)
                    connection?.trySend(CommandResult.ok(event).toJson())
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
                        connection?.trySend(NoticeResult("PONG").toJson())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(Citrine.TAG, "Failed to send pong response", e)
                    }
                }

                else -> {
                    try {
                        val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                        Log.d(Citrine.TAG, errorMessage)
                        connection?.trySend(errorMessage)
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
                connection?.trySend(NoticeResult.invalid("Error processing message").toJson())
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
        AuthRequiredForProtectedEvent,
    }

    suspend fun verifyEvent(event: Event, connection: Connection?, shouldVerify: Boolean): VerificationResult {
        if (!shouldVerify) {
            return VerificationResult.Valid
        }

        if (event.isExpired() && connection != null) {
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

        if (event.isProtected() && connection != null) {
            val remoteHost = connection.session.call.request.local.remoteHost
            val isLocalHost = remoteHost in listOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

            if (!isLocalHost && !connection.users.contains(event.pubKey)) {
                Log.d(Citrine.TAG, "auth required for protected event ${event.id}")
                return VerificationResult.AuthRequiredForProtectedEvent
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
        if (!event.verify()) {
            Log.d(Citrine.TAG, "event ${event.id} does not have a valid id or signature")
            return VerificationResult.InvalidId
        }
        return VerificationResult.Valid
    }

    suspend fun innerProcessEvent(event: Event, connection: Connection?, shouldVerify: Boolean = true): VerificationResult {
        val result = verifyEvent(event, connection, shouldVerify)
        when (result) {
            VerificationResult.AuthRequiredForProtectedEvent -> {
                connection?.trySend(AuthResult.challenge(connection.authChallenge).toJson())
                connection?.trySend(CommandResult.required(event, "this event may only be published by its author").toJson())
            }
            VerificationResult.InvalidId -> {
                connection?.trySend(
                    CommandResult.invalid(
                        event,
                        "event id hash verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.InvalidSignature -> {
                connection?.trySend(
                    CommandResult.invalid(
                        event,
                        "event signature verification failed",
                    ).toJson(),
                )
            }
            VerificationResult.Expired -> {
                connection?.trySend(CommandResult.invalid(event, "event expired").toJson())
            }
            VerificationResult.KindNotAllowed -> {
                connection?.trySend(CommandResult.invalid(event, "kind not allowed").toJson())
            }
            VerificationResult.PubkeyNotAllowed -> {
                connection?.trySend(CommandResult.invalid(event, "pubkey not allowed").toJson())
            }
            VerificationResult.TaggedPubkeyNotAllowed -> {
                connection?.trySend(CommandResult.invalid(event, "tagged pubkey not allowed").toJson())
            }
            VerificationResult.Deleted -> {
                connection?.trySend(CommandResult.invalid(event, "Event deleted").toJson())
            }
            VerificationResult.AlreadyInDatabase -> {
                connection?.trySend(CommandResult.duplicated(event).toJson())
            }
            VerificationResult.TaggedPubKeyMismatch -> {
                connection?.trySend(CommandResult.invalid(event, "Tagged event pubkey mismatch ${event.toJson()}").toJson())
            }
            VerificationResult.NewestEventAlreadyInDatabase -> {
                connection?.trySend(CommandResult.invalid(event, "newest event already in database").toJson())
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

                // if the event is ephemeral the response will be sent after the event is sent to subscriptions
                if (!event.isEphemeral()) {
                    connection?.trySend(CommandResult.ok(event).toJson())
                }
            }
        }
        return result
    }

    private suspend fun processEvent(event: Event, connection: Connection?) {
        innerProcessEvent(event, connection)
    }

    private suspend fun handleParameterizedReplaceable(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.instance).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection, sendEventToSubscriptions = false)
    }

    private suspend fun override(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(1)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
        HistoryDatabase.getDatabase(Citrine.instance).eventDao().insertEventWithTags(event.toEventWithTags(), connection = connection, sendEventToSubscriptions = false)
    }

    private fun isOffline(): Boolean {
        val connectivityManager = Citrine.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true

        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            } else null

        if (targetFile == null) {
            Log.d(Citrine.TAG, "$requestedPath not found")
            return call.respond(HttpStatusCode.NotFound, null)
        }

        resolver.openInputStream(targetFile.uri)?.use { input ->
            Log.d(Citrine.TAG, "${targetFile.name} sent")
            call.respondOutputStream(
                contentType = ContentType.defaultForFilePath(targetFile.name ?: "index.html")
            ) {
                input.copyTo(this)
            }
        } ?: run {
            Log.d(Citrine.TAG, "${targetFile.name} not sent")
            call.respond(HttpStatusCode.InternalServerError, null)
        }
    }


    fun randomFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    private val webClientServers = ConcurrentHashMap<String, WebClientServer>()

    fun startWebClientServer(
        clientName: String,
        rootUri: Uri
    ): WebClientServer {

        val port = randomFreePort()

        val server = embeddedServer(
            CIO,
            host = "127.0.0.1",
            port = port
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
            server = server
        )
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

    private fun HttpMethod.allowsRequestBody(): Boolean =
        this == HttpMethod.Post ||
            this == HttpMethod.Put ||
            this == HttpMethod.Patch ||
            this == HttpMethod.Delete

    private val hopByHopHeaders = setOf(
        HttpHeaders.Connection,
        HttpHeaders.ProxyAuthenticate,
        HttpHeaders.ProxyAuthorization,
        HttpHeaders.Trailer,
        HttpHeaders.TransferEncoding,
        HttpHeaders.Upgrade
    )

    private suspend fun proxyHttp(
        call: ApplicationCall,
        clientName: String,
    ) {
        val clientServer = webClientServers["/$clientName"]
            ?: return call.respond(HttpStatusCode.NotFound, null)

        // 🚀 Forward full path + query string
        val targetUrl = "http://127.0.0.1:${clientServer.port}${call.request.uri}"

        val response = proxyClient.request(targetUrl) {
            method = call.request.httpMethod

            headers {
                call.request.headers.forEach { key, values ->
                    if (!hopByHopHeaders.contains(key)) {
                        values.forEach { append(key, it) }
                    }
                }

                set(HttpHeaders.Host, "127.0.0.1:${clientServer.port}")
            }

            if (call.request.httpMethod.allowsRequestBody()) {
                val len = call.request.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull() ?: 0L
                if (len > 0) setBody(call.receiveStream())
            }
        }

        // Forward response headers
        response.headers.forEach { key, values ->
            if (!hopByHopHeaders.contains(key)) {
                values.forEach { call.response.headers.append(key, it) }
            }
        }

        call.respondBytesWriter(
            contentType = response.contentType(),
            status = response.status
        ) {
            response.bodyAsChannel().copyTo(this)
        }
    }


    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startKtorHttpServer(host: String, port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        Settings.webClients.forEach { (name, uriString) ->
            val uri = uriString.toUri()
            val clientServer = startWebClientServer(name, uri)
            webClientServers[name] = clientServer

            Log.d(
                Citrine.TAG,
                "Started web client '$name' on port ${clientServer.port}"
            )
        }

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
                         * Compression level to use for [Deflater].
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

                        val supportedNips = mutableListOf(1, 2, 4, 9, 11, 40, 45, 50, 59, 65, 70)
                        if (Settings.authEnabled) supportedNips.add(42)

                        call.respondText(
                            """
                            {
                              "name": "${Settings.name}",
                              "description": "${Settings.description}",
                              "pubkey": "${Settings.ownerPubkey}",
                              "contact": "${Settings.contact}",
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
                    connections.emit(connections.value + thisConnection)

                    val maxParallelMessages = 8

                    val job = launch {
                        thisConnection.sharedFlow
                            .buffer(capacity = maxParallelMessages) // decouples producer from consumers
                            .flatMapMerge(concurrency = maxParallelMessages) { message ->
                                flow {
                                    try {
                                        // process message on IO dispatcher
                                        withContext(Dispatchers.IO) {
                                            processNewRelayMessage(message, thisConnection)
                                        }
                                        emit(Unit) // emit something to complete the flow
                                    } catch (e: CancellationException) {
                                        Log.d(Citrine.TAG, "Message $message cancelled")
                                        throw e // propagate cancellation
                                    } catch (e: Exception) {
                                        Log.e(Citrine.TAG, "Failed to process message $message", e)
                                    }
                                }
                            }
                            .collect { } // we don’t need the results, just trigger processing
                    }
                    Log.d(Citrine.TAG, "New connection from ${this.call.request.local.remoteHost} ${thisConnection.name}")

                    if (Settings.authEnabled) {
                        thisConnection.trySend(AuthResult(thisConnection.authChallenge).toJson())
                    }

                    val messageChannel = Channel<String>(capacity = Channel.UNLIMITED)
                    launch {
                        for (message in messageChannel) {
                            thisConnection.messageResponseFlow.emit(message)
                        }
                    }

                    try {
                        incoming.asTextMessages()
                            .onEach { message ->
                                thisConnection.messageResponseFlow.emit(message) // decoupled by buffer
                            }
                            .catch { e ->
                                // Handle unexpected exceptions (not cancellations)
                                if (e !is CancellationException) {
                                    Log.d(Citrine.TAG, "Error processing message: $e", e)
                                    try {
                                        thisConnection.trySend(
                                            NoticeResult.invalid("Error processing message").toJson(),
                                        )
                                    } catch (sendEx: Exception) {
                                        Log.d(Citrine.TAG, sendEx.toString(), sendEx)
                                    }
                                }
                                // CancellationExceptions are handled by finally
                            }
                            .collect { } // we don’t need the results, just trigger processing
                    } finally {
                        // Always clean up, even on cancellation
                        removeConnection(thisConnection)
                        job.cancel()
                        Log.d(Citrine.TAG, "Connection closed from ${thisConnection.name}")
                    }
                }
            }
        }.start(wait = false)
    }

    suspend fun removeConnection(connection: Connection) {
        Log.d(Citrine.TAG, "Removing ${connection.name}!")
        connection.session.cancel()
        connection.finalize()
        connections.value.remove(connection)
        connections.emit(connections.value)
    }
}

data class WebClientServer(
    val name: String,
    val rootUri: Uri,
    val port: Int,
    val server: EmbeddedServer<*, *>
)


fun ReceiveChannel<Frame>.asTextMessages(): Flow<String> = receiveAsFlow() // Convert channel to Flow
    .filterIsInstance<Frame.Text>() // Only text frames
    .map { it.readText() } // Convert to String once
    .buffer(Channel.UNLIMITED) // Decouple producer from downstream collectors
    .catch { e -> println("Error reading frame: $e") } // Optional
