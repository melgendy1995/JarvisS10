package com.jarvis.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressTest {
    @Test
    fun prefersAWlanAddress() {
        val lan = LanAddress.pick(
            listOf(
                LanCandidate("10.0.0.5", wifi = false),
                LanCandidate("192.168.8.211", wifi = true),
            ),
        )
        assertEquals("192.168.8.211", lan)
    }

    @Test
    fun skipsLoopbackLinkLocalTailscaleAndMulticast() {
        val lan = LanAddress.pick(
            listOf(
                LanCandidate("127.0.0.1", wifi = true),
                LanCandidate("169.254.10.20", wifi = true),
                LanCandidate("100.101.102.103", wifi = true),
                LanCandidate("224.0.0.7", wifi = true),
            ),
        )
        assertNull(lan)
    }

    @Test
    fun acceptsEveryRfc1918Range() {
        for (ip in listOf("10.1.2.3", "172.16.0.9", "172.31.255.255", "192.168.0.1")) {
            assertEquals(ip, LanAddress.pick(listOf(LanCandidate(ip, wifi = false))))
        }
    }

    @Test
    fun skipsOutsideRfc1918() {
        assertNull(LanAddress.pick(listOf(LanCandidate("172.15.0.1", wifi = true))))
        assertNull(LanAddress.pick(listOf(LanCandidate("172.32.0.1", wifi = true))))
        assertNull(LanAddress.pick(listOf(LanCandidate("172.16", wifi = true))))
    }

    @Test
    fun displayFormatsThePort() {
        assertEquals("192.168.8.211:8088", LanAddress.display("192.168.8.211", 8088))
        assertEquals("No Wi-Fi", LanAddress.display(null, 8088))
        assertEquals("No Wi-Fi", LanAddress.display("", 8088))
    }
}
