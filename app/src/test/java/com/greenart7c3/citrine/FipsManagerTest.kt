package com.greenart7c3.citrine

import com.greenart7c3.citrine.service.FipsManager
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FipsManagerTest {
    @Test
    fun `picks fd00 slash 8 unique local address`() {
        val addresses = listOf(
            InetAddress.getByName("192.168.1.10"),
            InetAddress.getByName("fe80::1"),
            InetAddress.getByName("fd12:3456:789a::1"),
        )
        assertEquals("fd12:3456:789a:0:0:0:0:1", FipsManager.pickUlaAddress(addresses))
    }

    @Test
    fun `rejects loopback link local ipv4 and non ula ipv6`() {
        val addresses = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("::1"),
            InetAddress.getByName("fe80::1"),
            InetAddress.getByName("192.168.1.10"),
            // fc00::/8 is ULA too, but FIPS only assigns from fd00::/8
            InetAddress.getByName("fc00::1"),
            InetAddress.getByName("2001:db8::1"),
        )
        assertNull(FipsManager.pickUlaAddress(addresses))
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(FipsManager.pickUlaAddress(emptyList()))
    }
}
