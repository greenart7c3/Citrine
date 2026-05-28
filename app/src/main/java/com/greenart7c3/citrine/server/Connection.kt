package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
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

    // When the peer stops draining its socket the outgoing channel fills up and
    // trySend starts failing. We record when that started so the watchdog can
    // reap a stuck connection. 0 means the channel is currently healthy.
    @Volatile
    private var outgoingStalledSince: Long = 0L

    private val connectionScope = CoroutineScope(
        Citrine.instance.applicationScope.coroutineContext + SupervisorJob(),
    )

    init {
        connectionScope.launch {
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()

                // The session is already gone but the read loop hasn't reaped it yet.
                val sessionDead = session.outgoing.isClosedForSend

                // The peer hasn't drained the outgoing buffer for a while. This also
                // wedges Ktor's pinger (it sends pings with a suspending send), so the
                // pong timeout never fires and the connection would otherwise live
                // forever. Reap it regardless of whether it has open subscriptions.
                val stalledFor = outgoingStalledSince
                val outgoingStalled = stalledFor != 0L && now - stalledFor > 60_000

                // Idle connection with no open subscription: nothing keeps it useful.
                val idleWithoutSubscription = now - since > 10 * 60 * 1000 &&
                    !EventSubscription.containsConnection(this@Connection)

                if (sessionDead || outgoingStalled || idleWithoutSubscription) {
                    Log.d(
                        Citrine.TAG,
                        "Closing session for $name (sessionDead=$sessionDead stalled=$outgoingStalled idle=$idleWithoutSubscription)",
                    )
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
            val result = session.outgoing.trySend(Frame.Text(data))
            when {
                result.isSuccess -> outgoingStalledSince = 0L
                result.isClosed -> {
                    Log.d(Citrine.TAG, "Session is closed for connection $name $remoteAddress")
                    // Do not call removeConnection here — the webSocket finally block owns removal.
                }
                else -> {
                    // Channel is full: the peer isn't draining. Remember when this
                    // started so the watchdog can reap the connection if it persists.
                    if (outgoingStalledSince == 0L) {
                        outgoingStalledSince = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Error sending data to client $data", e)
        }
    }
}
