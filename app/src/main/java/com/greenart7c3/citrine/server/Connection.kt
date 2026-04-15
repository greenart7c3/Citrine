package com.greenart7c3.citrine.server

import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.Timer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class Connection(
    val session: DefaultWebSocketServerSession,
    var users: MutableSet<HexKey> = mutableSetOf(),
    val authChallenge: String = UUID.randomUUID().toString().substring(0..10),
) {
    val messageResponseFlow = MutableSharedFlow<String>()
    val sharedFlow = messageResponseFlow.asSharedFlow()

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
                            Citrine.instance.applicationScope.launch {
                                session.cancel()
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

    fun remoteAddress(): String = "${session.call.request.local.remoteHost} ${session.call.request.headers["User-Agent"]}"

    fun finalize() {
        timer.cancel()
        EventSubscription.closeAll(name)
    }

    fun trySend(data: String) {
        try {
            session.outgoing.trySend(Frame.Text(data)).onClosed {
                val connection = EventSubscription.getConnection(session)
                connection?.let {
                    Log.d(Citrine.TAG, "Session is closed for connection ${connection.name} ${connection.remoteAddress()}")
                    Citrine.instance.applicationScope.launch {
                        CustomWebSocketService.server?.removeConnection(connection)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Error sending data to client $data", e)
        }
    }

    /** Sends a pre-built [Frame] directly, avoiding a redundant String→ByteArray encoding step. */
    fun trySend(frame: Frame) {
        try {
            session.outgoing.trySend(frame).onClosed {
                val connection = EventSubscription.getConnection(session)
                connection?.let {
                    Log.d(Citrine.TAG, "Session is closed for connection ${connection.name} ${connection.remoteAddress()}")
                    Citrine.instance.applicationScope.launch {
                        CustomWebSocketService.server?.removeConnection(connection)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, "Error sending frame to client", e)
        }
    }

    /**
     * Suspending send that waits for the outgoing channel to accept the frame rather than
     * dropping it silently. Use this for event frames in subscribe() so that no events are
     * lost when the outgoing buffer is temporarily full.
     *
     * Returns true if the frame was sent, false if the channel was already closed.
     */
    suspend fun send(frame: Frame): Boolean = try {
        session.outgoing.send(frame)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(Citrine.TAG, "Error sending frame to client (connection closed)", e)
        false
    }
}
