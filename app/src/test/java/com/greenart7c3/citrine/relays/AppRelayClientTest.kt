package com.greenart7c3.citrine.relays

import com.greenart7c3.citrine.server.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppRelayClientTest {
    private val scope = CoroutineScope(Job())
    private val relayClient = AppRelayClient(scope)
    private var savedUseProxy = false
    private var savedProxyAllUrls = false

    @Before
    fun saveSettings() {
        savedUseProxy = Settings.useProxy
        savedProxyAllUrls = Settings.proxyAllUrls
    }

    @After
    fun restoreSettings() {
        Settings.useProxy = savedUseProxy
        Settings.proxyAllUrls = savedProxyAllUrls
        scope.cancel()
    }

    @Test
    fun onionHostsAreRecognizedWhateverTheirPortPathOrCase() {
        assertTrue(relayClient.isOnionUrl("ws://abcdef.onion"))
        assertTrue(relayClient.isOnionUrl("wss://ABCDEF.ONION:443/path?x=1#y"))
        assertFalse(relayClient.isOnionUrl("wss://relay.example.com/onion"))
        assertFalse(relayClient.isOnionUrl("wss://onion.example.com"))
    }

    @Test
    fun loopbackAndLanAddressesArePrivate() {
        assertTrue(relayClient.isPrivateIp("ws://127.0.0.1:4869"))
        assertTrue(relayClient.isPrivateIp("ws://localhost:4869"))
        assertTrue(relayClient.isPrivateIp("ws://192.168.1.10:4869"))
        assertTrue(relayClient.isPrivateIp("ws://172.16.0.1"))
        assertTrue(relayClient.isPrivateIp("ws://172.31.255.1"))
        assertFalse(relayClient.isPrivateIp("ws://172.32.0.1"))
        assertFalse(relayClient.isPrivateIp("wss://relay.damus.io"))
    }

    @Test
    fun nothingIsProxiedWhileTheProxyIsOff() {
        Settings.useProxy = false
        Settings.proxyAllUrls = true

        assertFalse(relayClient.shouldUseProxyFor("ws://abcdef.onion"))
        assertFalse(relayClient.shouldUseProxyFor("wss://relay.damus.io"))
    }

    @Test
    fun onlyOnionUrlsAreProxiedUnlessAllUrlsAre() {
        Settings.useProxy = true
        Settings.proxyAllUrls = false

        assertTrue(relayClient.shouldUseProxyFor("ws://abcdef.onion"))
        assertFalse(relayClient.shouldUseProxyFor("wss://relay.damus.io"))

        Settings.proxyAllUrls = true
        assertTrue(relayClient.shouldUseProxyFor("wss://relay.damus.io"))
    }

    @Test
    fun privateAddressesAreNeverProxied() {
        Settings.useProxy = true
        Settings.proxyAllUrls = true

        assertFalse(relayClient.shouldUseProxyFor("ws://127.0.0.1:4869"))
        assertFalse(relayClient.shouldUseProxyFor("ws://192.168.1.10:4869"))
    }
}
