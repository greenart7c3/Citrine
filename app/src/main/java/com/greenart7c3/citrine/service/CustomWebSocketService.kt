package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.database.EventStore
import com.greenart7c3.citrine.server.CustomWebSocketServer

object CustomWebSocketService {
    var server: CustomWebSocketServer? = null
    var eventStore: EventStore? = null
    var hasStarted = false
}
