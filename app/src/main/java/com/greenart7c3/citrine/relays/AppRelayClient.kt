package com.greenart7c3.citrine.relays

import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.okhttp.OkHttpWebSocket
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import kotlinx.coroutines.CoroutineScope

class AppRelayClient(scope: CoroutineScope) : RelayClient {
    override val factory = OkHttpWebSocket.Builder { url ->
        HttpClientManager.getHttpClient(shouldUseProxyFor(url.url))
    }

    override val client: NostrClient = NostrClient(factory, scope)

    override fun shouldUseProxyFor(url: String): Boolean {
        if (!Settings.useProxy) return false
        if (isPrivateIp(url)) return false
        if (Settings.proxyAllUrls) return true
        return isOnionUrl(url)
    }

    override fun isOnionUrl(url: String): Boolean {
        val host = url
            .substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .lowercase()
        return host.endsWith(".onion")
    }

    override fun isPrivateIp(url: String): Boolean = PRIVATE_ADDRESS_MARKERS.any { url.contains(it) }

    private companion object {
        val PRIVATE_ADDRESS_MARKERS = listOf("127.0.0.1", "localhost", "192.168.") + (16..31).map { "172.$it." }
    }
}
