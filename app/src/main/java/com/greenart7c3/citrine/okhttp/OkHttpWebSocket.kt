/**
 * Copyright (c) 2024 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.greenart7c3.citrine.okhttp

import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilderFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class OkHttpWebSocket(
    val url: String,
    val forceProxy: Boolean,
    val httpClient: (url: String, forceProxy: Boolean) -> OkHttpClient,
    val out: WebSocketListener,
) : WebSocket {
    private val listener = OkHttpWebsocketListener()
    private var socket: okhttp3.WebSocket? = null

    fun buildRequest() = Request.Builder().url(url.trim()).build()

    override fun connect() {
        socket = httpClient(url, forceProxy).newWebSocket(buildRequest(), listener)
    }

    inner class OkHttpWebsocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(
            webSocket: okhttp3.WebSocket,
            response: Response,
        ) = out.onOpen(
            response.receivedResponseAtMillis - response.sentRequestAtMillis,
            response.headers["Sec-WebSocket-Extensions"]?.contains("permessage-deflate") == true,
        )

        override fun onMessage(
            webSocket: okhttp3.WebSocket,
            text: String,
        ) = out.onMessage(text)

        override fun onClosing(
            webSocket: okhttp3.WebSocket,
            code: Int,
            reason: String,
        ) = out.onClosing(code, reason)

        override fun onClosed(
            webSocket: okhttp3.WebSocket,
            code: Int,
            reason: String,
        ) = out.onClosed(code, reason)

        override fun onFailure(
            webSocket: okhttp3.WebSocket,
            t: Throwable,
            response: Response?,
        ) = out.onFailure(t, response?.message)
    }

    class Builder(
        val forceProxy: Boolean,
        val httpClient: (String, Boolean) -> OkHttpClient,
    ) : WebsocketBuilder {
        // Called when connecting.
        override fun build(
            url: String,
            out: WebSocketListener,
        ) = OkHttpWebSocket(url, forceProxy, httpClient, out)
    }

    class BuilderFactory(
        val httpClient: (String, Boolean) -> OkHttpClient,
    ) : WebsocketBuilderFactory {
        override fun build(
            url: String,
            forceProxy: Boolean,
        ) = Builder(forceProxy, httpClient)
    }

    override fun cancel() {
        socket?.cancel()
    }

    override fun send(msg: String): Boolean = socket?.send(msg) == true
}
