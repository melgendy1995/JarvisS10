package com.jarvis.core.camera

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSupportTest {
    @Test
    fun fullProfileIs720pAt15To30() {
        val full = CameraProfiles.forTier(ThermalTier.FULL)!!
        assertEquals(1280, full.width)
        assertEquals(720, full.height)
        assertEquals(15, full.fpsMin)
        assertEquals(30, full.fpsMax)
        val reduced = CameraProfiles.forTier(CameraThermal.tier(CameraThermal.MODERATE))
        assertEquals(640, reduced!!.width)
        assertEquals(480, reduced.height)
        assertTrue(reduced.fpsMax < full.fpsMax)
        assertNull(CameraProfiles.forTier(CameraThermal.tier(CameraThermal.SEVERE)))
        assertEquals(ThermalTier.FULL, CameraThermal.tier(CameraThermal.LIGHT))
        assertEquals(ThermalTier.STOP, CameraThermal.tier(4))
    }

    @Test
    fun classifiesTailscaleAndLan() {
        assertEquals(HostKind.TAILSCALE, classify(ipv4(100, 64, 0, 1)))
        assertEquals(HostKind.TAILSCALE, classify(ipv4(100, 127, 255, 255)))
        assertEquals(HostKind.LAN, classify(ipv4(100, 63, 0, 1)))
        assertEquals(HostKind.LAN, classify(ipv4(100, 128, 0, 1)))
        assertEquals(HostKind.LAN, classify(ipv4(192, 168, 1, 20)))
        assertEquals(HostKind.LAN, classify(ipv4(10, 0, 0, 4)))
        assertEquals(HostKind.IGNORE, classify(ipv4(127, 0, 0, 1)))
        assertEquals(HostKind.IGNORE, classify(ipv4(169, 254, 1, 1)))
        val ends = CameraEndpoints.from(
            listOf(ipv4(192, 168, 1, 20), ipv4(100, 89, 1, 4), ipv4(127, 0, 0, 1)),
            8088,
        )
        assertEquals(listOf("http://192.168.1.20:8088/"), ends.lan)
        assertEquals(listOf("http://100.89.1.4:8088/"), ends.tailscale)
        assertEquals("No LAN address", CameraEndpoints(emptyList(), emptyList()).lanText())
        assertEquals("Tailscale is not connected", ends.copy(tailscale = emptyList()).tailscaleText())
    }

    @Test
    fun basicAuthAndConstantCompare() {
        val header = "Basic " + Base64.getEncoder().encodeToString("jarvis:s3cret".toByteArray())
        assertEquals("s3cret", basicPassword(header))
        assertNull(basicPassword(null))
        assertNull(basicPassword("Bearer token"))
        assertTrue(passwordAccepted("s3cret", "s3cret"))
        assertFalse(passwordAccepted("nope", "s3cret"))
        assertFalse(passwordAccepted(null, "s3cret"))
        assertFalse(passwordAccepted("s3cret", ""))
        assertEquals("abc", formPassword("password=abc&x=1"))
        assertEquals("a b", formPassword("password=a%20b"))
        assertEquals("tok", cookieValue("other=1; jarvis_cam=tok", "jarvis_cam"))
    }

    @Test
    fun sdpJsonRoundTrip() {
        val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n"
        val json = JsonText.answer(sdp)
        assertEquals("answer", JsonText.stringField(json, "type"))
        assertEquals(sdp, JsonText.stringField(json, "sdp"))
        val offer = "{\"type\":\"offer\",\"sdp\":${JsonText.quote(sdp)}}"
        assertEquals(sdp, JsonText.stringField(offer, "sdp"))
        assertEquals("v=0\r\n", normalizeSdp("v=0\n"))
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): ByteArray {
        return byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    }
}
