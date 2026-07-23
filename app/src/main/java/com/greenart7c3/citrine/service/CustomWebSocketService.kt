package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.server.CustomWebSocketServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CustomWebSocketService {
    private val _running = MutableStateFlow(false)

    val running: StateFlow<Boolean> = _running.asStateFlow()

    var server: CustomWebSocketServer? = null
        set(value) {
            field = value
            _running.value = value != null
        }

    var hasStarted = false
}
