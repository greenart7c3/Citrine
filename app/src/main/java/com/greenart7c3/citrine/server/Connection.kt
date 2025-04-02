package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.onClosed

class Connection(val session: DefaultWebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "user${lastId.getAndIncrement()}"

    fun finalize() {
        EventSubscription.closeAll(name)
    }
}

fun DefaultWebSocketServerSession.trySend(data: String) {
    try {
        outgoing.trySend(Frame.Text(data)).onClosed {
            Log.d(Citrine.TAG, "Session is closed")
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.d(Citrine.TAG, "Error sending data to client $data", e)
    }
}
