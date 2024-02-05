package com.greenart7c3.citrine

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException


class CustomWebSocketServer(private val port: Int) {
    private lateinit var server: ApplicationEngine

    fun port(): Int {
        return server.environment.connectors.first().port
    }

    fun start() {
        server = startKtorHttpServer(port)
    }

    fun stop() {
        server.stop(1000)
    }

    private fun startKtorHttpServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, port = port) {
            install(WebSockets)

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
                            "supported_nips": [],
                            "software": "https://github.com/greenart7c3/Citrine",
                            "version": "0.0.1"
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
                                    println("Received WebSocket message: ${frame.readText()}")
                                }
                                else -> {}
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        // Channel closed
                    }
                }
            }
        }.start(wait = false)
    }
}