package com.greenart7c3.citrine

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class CustomWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    override fun onStart() {
        Log.d("server","Server started $address")
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d("server","Connection opened from ${conn?.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d("server","Connection closed by ${conn?.remoteSocketAddress}, Code: $code, Reason: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        Log.d("server","Message received from ${conn?.remoteSocketAddress}: $message")
        conn?.send("Server received: $message")
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.d("server","Error occurred: ${ex?.message}")
    }
}