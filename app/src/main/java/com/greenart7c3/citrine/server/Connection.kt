package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.negentropy.Negentropy
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Connection(
    val session: DefaultWebSocketServerSession,
    var users: MutableSet<HexKey> = mutableSetOf(),
    val authChallenge: String = UUID.randomUUID().toString().substring(0..10),
) {
    val messageResponseFlow = MutableSharedFlow<String>()
    val sharedFlow = messageResponseFlow.asSharedFlow()

    val negentropySessions: MutableMap<String, Negentropy> = ConcurrentHashMap()

    val closed = AtomicBoolean(false)

    val since = System.currentTimeMillis()

    private val connectionScope = CoroutineScope(
        Citrine.instance.applicationScope.coroutineContext + SupervisorJob(),
    )

    init {
        connectionScope.launch {
            while (isActive) {
                delay(60_000)
                val hasTenMinutesPassed = System.currentTimeMillis() - since > 10 * 60 * 1000
                if (hasTenMinutesPassed && !EventSubscription.containsConnection(this@Connection)) {
                    Log.d(Citrine.TAG, "Closing session due to inactivity for $name")
                    try {
                        session.cancel()
                    } catch (_: Throwable) {
                    }
                    break
                }
            }
        }
    }

    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "user${lastId.getAndIncrement()}"

    val remoteAddress: String = try {
        "${session.call.request.local.remoteHost} ${session.call.request.headers["User-Agent"]}"
    } catch (e: Exception) {
        Log.w(Citrine.TAG, "Could not resolve remote address", e)
        "unknown"
    }

    fun finalize() {
        connectionScope.cancel()
        EventSubscription.closeAll(name)
        negentropySessions.clear()
    }

    fun trySend(data: String) {
        try {
            session.outgoing.trySend(Frame.Text(data)).onClosed {
                Log.d(Citrine.TAG, "Session is closed for connection $name $remoteAddress")
                // Do not call removeConnection here — the webSocket finally block owns removal.
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Error sending data to client $data", e)
        }
    }
}
