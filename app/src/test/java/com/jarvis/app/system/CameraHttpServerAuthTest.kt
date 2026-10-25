package com.jarvis.app.system

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera HTTP server is the only caller that can start or extend the
 * camera feed. These tests pin the ordering: an unauthenticated request is 401
 * and never touches an [CameraOnDemandHooks] method, and only authenticated
 * requests do. 401 responses therefore cannot start, restart or extend the
 * camera (httpd-side of the policy in [CameraStopPolicy]).
 */
class CameraHttpServerAuthTest {
    private class RecordingHooks : CameraOnDemandHooks {
        var mediaAccessCalls = 0
        var streamOpened = 0
        var snapshotTouched = 0
        var viewerClosedCalls = 0
        var peerCounts = 0

        override fun mediaAccess(): MediaAccess {
            mediaAccessCalls++
            return MediaAccess.Allow
        }

        override fun streamViewerOpened(): String {
            streamOpened++
            return "stream-test"
        }

        override fun snapshotViewerTouched() {
            snapshotTouched++
        }

        override fun viewerClosed(id: String) {
            viewerClosedCalls++
        }

        override fun peerCountChanged(count: Int) {
            peerCounts++
        }
    }

    private class FakeSession(
        private val httpMethod: NanoHTTPD.Method,
        private val path: String,
        private val requestHeaders: MutableMap<String, String>,
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() = Unit

        override fun getCookies(): NanoHTTPD.CookieHandler = throw UnsupportedOperationException()

        override fun getHeaders(): Map<String, String> = requestHeaders

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getMethod(): NanoHTTPD.Method = httpMethod

        override fun getParms(): Map<String, String> = emptyMap()

        override fun getParameters(): Map<String, List<String>> = emptyMap()

        override fun getQueryParameterString(): String = ""

        override fun getUri(): String = path

        override fun getRemoteIpAddress(): String = "127.0.0.1"

        override fun getRemoteHostName(): String = "localhost"

        override fun parseBody(files: Map<String, String>) {
            throw UnsupportedOperationException()
        }
    }

    private fun server(hooks: RecordingHooks): CameraHttpServer {
        return CameraHttpServer(
            port = 1,
            viewerHtml = { "<html>viewer</html>" },
            password = { "camera-secret" },
            hub = FrameHub().apply { publish(ByteArray(64)) },
            answerer = { null },
            onDemand = hooks,
        )
    }

    @Test
    fun anUnauthenticatedPageRequestIs401AndNeverStartsTheCamera() {
        val hooks = RecordingHooks()
        val response = server(hooks).serve(FakeSession(NanoHTTPD.Method.GET, "/", mutableMapOf()))
        assertEquals(401, response.status.requestStatus)
        assertEquals(0, hooks.mediaAccessCalls)
        assertEquals(0, hooks.snapshotTouched)
        assertEquals(0, hooks.streamOpened)
    }

    @Test
    fun anUnauthenticatedStreamAndSnapshotAre401WithoutExtendingAnything() {
        val hooks = RecordingHooks()
        val http = server(hooks)
        for (path in listOf("/stream", "/snapshot.jpg")) {
            val response = http.serve(FakeSession(NanoHTTPD.Method.GET, path, mutableMapOf()))
            assertEquals(401, response.status.requestStatus)
        }
        assertEquals(0, hooks.snapshotTouched)
        assertEquals(0, hooks.streamOpened)
        assertEquals(0, hooks.mediaAccessCalls)
    }

    @Test
    fun aBadPasswordIsAlso401WithNoHookCalls() {
        val hooks = RecordingHooks()
        val headers = mutableMapOf("authorization" to basicAuth("not-the-password"))
        val response = server(hooks).serve(FakeSession(NanoHTTPD.Method.GET, "/", headers))
        assertEquals(401, response.status.requestStatus)
        assertEquals(0, hooks.mediaAccessCalls)
    }

    @Test
    fun anAuthenticatedPageRequestReachesTheGate() {
        val hooks = RecordingHooks()
        val headers = mutableMapOf("authorization" to basicAuth("camera-secret"))
        val response = server(hooks).serve(FakeSession(NanoHTTPD.Method.GET, "/", headers))
        assertEquals(1, hooks.mediaAccessCalls)
        // The page itself neither adopts a stream viewer nor extends snapshots.
        assertEquals(0, hooks.streamOpened)
        assertEquals(0, hooks.snapshotTouched)
        assertTrue(response.status.requestStatus != 401)
        assertFalse(response.mimeType.isNullOrBlank())
    }

    @Test
    fun onlyAnAuthenticatedSnapshotHitExtendsTheViewer() {
        val hooks = RecordingHooks()
        val headers = mutableMapOf("authorization" to basicAuth("camera-secret"))
        server(hooks).serve(FakeSession(NanoHTTPD.Method.GET, "/snapshot.jpg", headers))
        assertEquals(1, hooks.snapshotTouched)
        assertEquals(0, hooks.streamOpened)
        assertEquals(1, hooks.mediaAccessCalls)
    }

    private fun basicAuth(password: String): String {
        val encoded = Base64.getEncoder().encodeToString("jarvis:$password".toByteArray())
        return "Basic $encoded"
    }
}
