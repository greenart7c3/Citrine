package com.greenart7c3.citrine.server

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class Connection(
    val session: DefaultWebSocketServerSession,
    var user: HexKey? = null,
    val authChallenge: String = UUID.randomUUID().toString().substring(0..10),
) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "user${lastId.getAndIncrement()}"

    fun finalize() {
        EventSubscription.closeAll(name)
    }
}
