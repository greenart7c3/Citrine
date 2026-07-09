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
import kotlinx.coroutines.channels.Channel
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

    // Outbound frames are staged here and drained by a single writer coroutine using the
    // suspending session.send(). Ktor's session.outgoing only buffers a handful of frames,
    // so writing to it with trySend() silently dropped frames whenever a REQ streamed more
    // events than the buffer could hold.
    private val outgoingQueue = Channel<String>(capacity = OUTGOING_QUEUE_CAPACITY)

    init {
        connectionScope.launch {
            try {
                for (message in outgoingQueue) {
                    session.send(Frame.Text(message))
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, "Writer stopped for connection $name", e)
            }
        }
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

        // Deep enough to absorb bursts (large REQ results, fanout floods) without
        // unbounded memory growth. Suspending producers use send() for backpressure;
        // non-suspending producers use trySend(), which disconnects a consumer that
        // has fallen this far behind instead of silently corrupting its stream.
        private const val OUTGOING_QUEUE_CAPACITY = 1024
    }
    val name = "user${lastId.getAndIncrement()}"

    val remoteAddress: String = try {
        "${session.call.request.local.remoteHost} ${session.call.request.headers["User-Agent"]}"
    } catch (e: Exception) {
        Log.w(Citrine.TAG, "Could not resolve remote address", e)
        "unknown"
    }

    fun finalize() {
        outgoingQueue.close()
        connectionScope.cancel()
        EventSubscription.closeAll(name)
        negentropySessions.clear()
    }

    /**
     * Enqueues a frame, suspending when the outbound queue is full so slow clients apply
     * backpressure to the producer instead of dropping frames. Use on high-volume paths
     * (REQ result streaming, per-EVENT command results).
     */
    suspend fun send(data: String) {
        if (closed.get()) return
        try {
            outgoingQueue.send(data)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Session is closed for connection $name $remoteAddress")
            // Do not call removeConnection here — the webSocket finally block owns removal.
        }
    }

    fun trySend(data: String) {
        if (closed.get()) return
        val result = outgoingQueue.trySend(data)
        if (result.isClosed) {
            Log.d(Citrine.TAG, "Session is closed for connection $name $remoteAddress")
            // Do not call removeConnection here — the webSocket finally block owns removal.
        } else if (result.isFailure) {
            // The client is not reading fast enough to keep up. Disconnect it rather than
            // silently dropping frames mid-stream, which corrupts REQ/OK semantics.
            Log.w(Citrine.TAG, "Outgoing queue full for connection $name $remoteAddress, closing slow consumer")
            try {
                session.cancel()
            } catch (_: Throwable) {
            }
        }
    }
}
