package com.greenart7c3.citrine

import android.app.Application
import android.content.ContentResolver
import android.util.Log
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.EventInterface

class Citrine : Application() {
    override fun onCreate() {
        super.onCreate()

        instance = this
        LocalPreferences.loadSettingsFromEncryptedStorage(this)
    }

    fun contentResolverFn(): ContentResolver = contentResolver

    companion object {
        const val TAG = "Citrine"

        @Volatile
        private var instance: Citrine? = null

        fun getInstance(): Citrine =
            instance ?: synchronized(this) {
                instance ?: Citrine().also { instance = it }
            }
    }
}

class RelayListener(
    val onReceiveEvent: (relay: Relay, subscriptionId: String, event: Event) -> Unit,
) : Relay.Listener {
    override fun onAuth(relay: Relay, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
    }

    override fun onBeforeSend(relay: Relay, event: EventInterface) {
        Log.d("RelayListener", "Sending event ${event.id()} to relay ${relay.url}")
    }

    override fun onError(relay: Relay, subscriptionId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subscriptionId")
    }

    override fun onEvent(relay: Relay, subscriptionId: String, event: Event, afterEOSE: Boolean) {
        Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subscriptionId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subscriptionId, event)
    }

    override fun onNotify(relay: Relay, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: Relay, type: Relay.StateType, channel: String?) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
    }

    override fun onSend(relay: Relay, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: Relay, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}

class RelayListener2(
    val onReceiveEvent: (relay: Relay, subscriptionId: String, event: Event) -> Unit,
    val onEOSE: (relay: Relay) -> Unit,
    val onErrorFunc: (relay: Relay, subscriptionId: String, error: Error) -> Unit,
    val onAuthFunc: (relay: Relay, challenge: String) -> Unit,
) : Relay.Listener {
    override fun onAuth(relay: Relay, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
        onAuthFunc(relay, challenge)
    }

    override fun onBeforeSend(relay: Relay, event: EventInterface) {
        Log.d("RelayListener", "Sending event ${event.id()} to relay ${relay.url}")
    }

    override fun onError(relay: Relay, subscriptionId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subscriptionId")
        onErrorFunc(relay, subscriptionId, error)
    }

    override fun onEvent(relay: Relay, subscriptionId: String, event: Event, afterEOSE: Boolean) {
        // Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subscriptionId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subscriptionId, event)
    }

    override fun onNotify(relay: Relay, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: Relay, type: Relay.StateType, channel: String?) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
        if (type == Relay.StateType.EOSE || type == Relay.StateType.DISCONNECTING) {
            onEOSE(relay)
        }
    }

    override fun onSend(relay: Relay, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: Relay, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}
