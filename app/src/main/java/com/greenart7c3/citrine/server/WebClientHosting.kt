package com.greenart7c3.citrine.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
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
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyTo
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

data class WebClientServer(
    val name: String,
    val rootUri: Uri,
    val port: Int,
    val server: EmbeddedServer<*, *>,
)

private fun DocumentFile?.findFileRecursive(path: String): DocumentFile? {
    if (this == null) return null
    val parts = path.split("/")
    var current: DocumentFile = this
    for (part in parts) {
        val next = current.findFile(part) ?: return null
        current = next
    }
    return current
}

private fun randomFreePort(): Int = ServerSocket(0).use { it.localPort }

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

internal class WebClientHosting(private val context: Context) {
    private val webClientServers = ConcurrentHashMap<String, WebClientServer>()

    private val resolver: ContentResolver = context.contentResolver

    private val proxyClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        expectSuccess = false
    }

    fun stopAll() {
        webClientServers.forEach {
            it.value.server.stop()
        }
    }

    suspend fun proxyOrNotFound(call: ApplicationCall) {
        val clientName = extractClientName(call.request.host())
            ?: return call.respond(HttpStatusCode.NotFound, null)

        if (!webClientServers.containsKey("/$clientName")) {
            call.respond(HttpStatusCode.NotFound, null)
            return
        }

        proxyHttp(call, clientName)
    }

    suspend fun proxiedClientSubdomain(call: ApplicationCall): Boolean {
        val clientName = extractClientName(call.request.host())

        if (clientName != null && webClientServers.containsKey("/$clientName")) {
            proxyHttp(call, clientName)
            return true
        }
        return false
    }

    private suspend fun serveIndex(
        call: ApplicationCall,
        rootUri: Uri,
    ) {
        val requestedPath = call.request.uri.trimStart('/')
        Log.d(Citrine.TAG, requestedPath)

        val root = DocumentFile.fromTreeUri(context, rootUri)
        if (root == null) {
            Log.d(Citrine.TAG, "$requestedPath not found")
            return call.respond(HttpStatusCode.InternalServerError, null)
        }

        val requestedFile = root.findFileRecursive(requestedPath)?.takeIf { it.exists() && it.isFile }
        val isSpaRoute = requestedPath.isEmpty() || !requestedPath.contains('.')
        val targetFile = requestedFile
            ?: if (isSpaRoute) {
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

    private fun startWebClientServer(
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
     * Falls back to index.html for extension-less routes and to 404.html (with a 404 status)
     * when nothing matches, per NIP-5A (https://github.com/nostr-protocol/nips/blob/master/5A.md).
     */
    private suspend fun serveNsiteFileFromDir(
        call: ApplicationCall,
        rootDir: File,
    ) {
        val requestedPath = call.request.uri.substringBefore('?').trimStart('/')
        val rootCanonical = rootDir.canonicalFile

        fun resolveInsideRoot(path: String): File? {
            if (path.isEmpty()) return null
            val candidate = File(rootCanonical, path).canonicalFile
            return if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)) {
                candidate
            } else {
                null
            }
        }

        var target = resolveInsideRoot(requestedPath)?.takeIf { it.isFile }
        if (target == null && (requestedPath.isEmpty() || !requestedPath.substringAfterLast('/').contains('.'))) {
            target = resolveInsideRoot("index.html")?.takeIf { it.isFile }
        }
        if (target == null) {
            target = resolveInsideRoot("404.html")?.takeIf { it.isFile }
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

    private fun startNsiteServerFor(
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
                    serveNsiteFileFromDir(call, rootDir)
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

    fun startNsiteServer(nsite: NsiteInfo) {
        val dir = File(context.filesDir, "nsites/${nsite.folderName}")
        if (!dir.isDirectory) return
        val key = "/${nsite.folderName}"
        webClientServers.remove(key)?.server?.stop()
        webClientServers[key] = startNsiteServerFor(nsite.folderName, dir)
        Log.d(Citrine.TAG, "Started nsite '${nsite.folderName}'")
    }

    fun stopNsiteServer(folderName: String) {
        webClientServers.remove("/$folderName")?.server?.stop()
    }

    @Suppress("NoComments")
    private suspend fun proxyHttp(
        call: ApplicationCall,
        clientName: String,
    ) {
        val clientServer = webClientServers["/$clientName"]
            ?: return call.respond(HttpStatusCode.NotFound, null)

        val targetUrl = "http://127.0.0.1:${clientServer.port}${call.request.uri}"

        proxyClient.prepareRequest(targetUrl) {
            method = call.request.httpMethod

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
            val dir = File(context.filesDir, "nsites/${nsite.folderName}")
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
}
