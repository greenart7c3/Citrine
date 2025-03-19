package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.service.CustomWebSocketService
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.launch

class Connection(
    val session: DefaultWebSocketServerSession,
    var user: HexKey? = null,
    val authChallenge: String = UUID.randomUUID().toString().substring(0..10),
) {
    val since = System.currentTimeMillis()
    val timer = Timer()

    init {
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    val hasTenMinutesPassed = System.currentTimeMillis() - since > 10 * 60 * 1000
                    if (hasTenMinutesPassed) {
                        if (!EventSubscription.containsConnection(this@Connection)) {
                            Log.d(Citrine.TAG, "Closing session due to inactivity")
                            finalize()
                            Citrine.getInstance().applicationScope.launch {
                                session.close()
                                CustomWebSocketService.server?.removeConnection(this@Connection)
                            }
                        }
                    }
                }
            },
            0,
            60000,
        )
    }

    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "user${lastId.getAndIncrement()}"

    fun remoteAddress(): String {
        return "${session.call.request.local.remoteHost} ${session.call.request.headers["User-Agent"]}"
    }

    fun finalize() {
        timer.cancel()
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
