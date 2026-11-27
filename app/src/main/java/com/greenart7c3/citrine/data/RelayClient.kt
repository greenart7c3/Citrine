package com.greenart7c3.citrine.data

import com.greenart7c3.citrine.okhttp.OkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient

/** The outbound relay connections: the Quartz client, how it dials, and which URLs go through the proxy. */
interface RelayClient {
    val client: NostrClient

    /** Builds the WebSocket connections of a [NostrClient]; ad-hoc clients (crash report upload) use it too. */
    val factory: OkHttpWebSocket.Builder

    /** Whether a connection to [url] goes through the SOCKS proxy (built-in Tor or Orbot). */
    fun shouldUseProxyFor(url: String): Boolean

    fun isOnionUrl(url: String): Boolean

    /** Whether [url] points at this device or the local network, which is never proxied. */
    fun isPrivateIp(url: String): Boolean
}
