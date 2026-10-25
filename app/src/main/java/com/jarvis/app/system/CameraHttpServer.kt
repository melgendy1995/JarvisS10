package com.jarvis.app.system

import android.content.Context
import android.util.Log
import com.jarvis.core.camera.JsonText
import com.jarvis.core.camera.basicPassword
import com.jarvis.core.camera.cookieValue
import com.jarvis.core.camera.formPassword
import com.jarvis.core.camera.passwordAccepted
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.LinkedHashSet

/**
 * NanoHTTPD on 0.0.0.0 so LAN and Tailscale (100.x) can both connect.
 * Basic auth and the login cookie both compare the password in constant time.
 * The password is never written to the response or the log.
 * POST bodies are read from the socket. NanoHTTPD's temp-file parser is not used.
 *
 * The server lives with the app, not with a camera session. When the camera is
 * off, an authenticated media request starts it on demand; the feed itself is
 * waited for up to [FRAME_WAIT_MS] so the page never shows a black frame or a
 * placeholder error while the camera is coming up.
 */
internal class CameraHttpServer(
    context: Context,
    private val port: Int,
    private val password: () -> String,
    private val hub: FrameHub,
    private val answerer: (String) -> String?,
    private val onDemand: CameraOnDemandHooks,
) : NanoHTTPD("0.0.0.0", port) {
    private val viewer = context.resources.openRawResource(com.jarvis.app.R.raw.camera_viewer)
        .bufferedReader()
        .use { it.readText() }
    private val sessions = Collections.synchronizedSet(LinkedHashSet<String>())
    private val random = SecureRandom()

    fun startSafely(): Boolean {
        return try {
            start(30_000, false)
            Log.i(TAG, "server listening 0.0.0.0:$listeningPort")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "bind failed port=$port: ${error.javaClass.simpleName}")
            false
        }
    }

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val path = session.uri ?: "/"
        if (method == Method.POST && path == "/login") return login(session)
        if (!authorized(session)) {
            Log.i(TAG, "$method $path 401")
            val html = method == Method.GET && (path == "/" || path == "/login" || path == "/index.html")
            return unauthorized(if (html) LOGIN else "Unauthorized", if (html) "text/html; charset=utf-8" else "text/plain; charset=utf-8")
        }
        val response = when {
            method == Method.GET && (path == "/" || path == "/index.html") -> page()
            method == Method.GET && path == "/snapshot.jpg" -> snapshot()
            method == Method.GET && path == "/stream" -> stream()
            method == Method.POST && path == "/offer" -> offer(session)
            method == Method.HEAD && (path == "/" || path == "/snapshot.jpg" || path == "/stream") ->
                text(Response.Status.OK, "text/plain", "")
            else -> text(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found")
        }
        Log.i(TAG, "$method $path ${response.status.requestStatus}")
        return response
    }

    /** Authenticated media path; starts the camera when it is off. */
    private fun gate(): Response? {
        return when (val access = onDemand.mediaAccess()) {
            is MediaAccess.Allow -> null
            is MediaAccess.Refuse -> text(Response.Status.SERVICE_UNAVAILABLE, "text/plain; charset=utf-8", access.reason)
        }
    }

    /** Waits out the camera coming up so the page never renders an error first. */
    private fun hasFrameOrWait(): Boolean {
        if ((hub.snapshot()?.size ?: 0) >= 4) return true
        return hub.awaitFirst(FRAME_WAIT_MS)
    }

    private fun page(): Response {
        return gate() ?: text(Response.Status.OK, "text/html; charset=utf-8", viewer)
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val expected = password()
        if (expected.isEmpty()) return false
        val token = cookieValue(session.headers["cookie"], COOKIE)
        if (token != null && sessions.contains(token)) return true
        return passwordAccepted(basicPassword(session.headers["authorization"]), expected)
    }

    private fun login(session: IHTTPSession): Response {
        val body = readBody(session) ?: return text(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "Too large")
        val expected = password()
        if (!passwordAccepted(formPassword(body), expected)) {
            Log.i(TAG, "POST /login 401")
            return unauthorized(LOGIN, "text/html; charset=utf-8")
        }
        val token = issue()
        Log.i(TAG, "POST /login 302")
        return text(Response.Status.FOUND, "text/plain", "").apply {
            addHeader("Location", "/")
            addHeader("Set-Cookie", "$COOKIE=$token; HttpOnly; Path=/; SameSite=Lax; Max-Age=43200")
        }
    }

    private fun offer(session: IHTTPSession): Response {
        val refused = gate()
        if (refused != null) return refused
        val body = readBody(session) ?: return text(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "Too large")
        val type = JsonText.stringField(body, "type")
        val sdp = JsonText.stringField(body, "sdp")
        if (type != "offer" || sdp.isNullOrBlank()) {
            Log.i(TAG, "POST /offer 400")
            return text(Response.Status.BAD_REQUEST, "text/plain", "Bad offer")
        }
        hasFrameOrWait()
        val answer = answerer(sdp)
        if (answer.isNullOrBlank()) {
            Log.i(TAG, "POST /offer 503")
            return text(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "WebRTC unavailable")
        }
        return text(Response.Status.OK, "application/json; charset=utf-8", JsonText.answer(answer))
    }

    private fun snapshot(): Response {
        val refused = gate()
        if (refused != null) return refused
        onDemand.snapshotViewerTouched()
        hasFrameOrWait()
        val jpeg = hub.snapshot()
        if (jpeg == null || jpeg.size < 4) {
            return text(Response.Status.SERVICE_UNAVAILABLE, "text/plain; charset=utf-8", "Camera is starting")
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(jpeg),
            jpeg.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true)
        }
    }

    private fun stream(): Response {
        val refused = gate()
        if (refused != null) return refused
        hasFrameOrWait()
        val first = hub.snapshot()
        if (first == null || first.size < 4) {
            return text(Response.Status.SERVICE_UNAVAILABLE, "text/plain; charset=utf-8", "Camera is starting")
        }
        val id = onDemand.streamViewerOpened()
        return NanoHTTPD.newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            MjpegStream(hub) { onDemand.viewerClosed(id) },
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Connection", "close")
        }
    }

    private fun issue(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        synchronized(sessions) {
            sessions.add(token)
            while (sessions.size > 16) {
                val oldest = sessions.firstOrNull() ?: break
                sessions.remove(oldest)
            }
        }
        return token
    }

    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0L || length > MAX_BODY) return null
        if (length == 0L) return ""
        val buf = ByteArray(length.toInt())
        val input = session.inputStream
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun unauthorized(body: String, mime: String): Response {
        return text(Response.Status.UNAUTHORIZED, mime, body).apply {
            addHeader("WWW-Authenticate", "Basic realm=\"Jarvis\"")
        }
    }

    private fun text(status: Response.Status, mime: String, body: String): Response {
        return newFixedLengthResponse(status, mime, body).apply {
            addHeader("Cache-Control", "no-store")
            closeConnection(true)
        }
    }

    private class MjpegStream(
        private val hub: FrameHub,
        private val onClose: () -> Unit,
    ) : InputStream() {
        @Volatile
        private var closed = false
        private var pending = ByteArray(0)
        private var offset = 0
        private var version = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            while (offset >= pending.size) {
                if (closed) return -1
                val frame = hub.awaitAfter(version, 500)
                if (closed || hub.isClosed()) return -1
                if (frame == null) continue
                version = frame.version
                pending = multipart(frame.jpeg)
                offset = 0
            }
            val n = minOf(len, pending.size - offset)
            System.arraycopy(pending, offset, buffer, off, n)
            offset += n
            if (offset >= pending.size) pending = ByteArray(0)
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }

        private fun multipart(jpeg: ByteArray): ByteArray {
            val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()
            val tail = "\r\n".toByteArray()
            val out = ByteArray(head.size + jpeg.size + tail.size)
            System.arraycopy(head, 0, out, 0, head.size)
            System.arraycopy(jpeg, 0, out, head.size, jpeg.size)
            System.arraycopy(tail, 0, out, head.size + jpeg.size, tail.size)
            return out
        }
    }

    private companion object {
        const val TAG = "JarvisCamera"
        const val COOKIE = "jarvis_cam"
        const val MAX_BODY = 262_144L

        /** Grace for an on-demand camera start before the media endpoint gives up. */
        const val FRAME_WAIT_MS = 5_000L
        val LOGIN = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Jarvis</title>
            <style>
              body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #000; color: #cfe6f5; font-family: sans-serif; }
              form { display: grid; gap: 12px; width: min(320px, 90vw); }
              input { background: #000; color: #cfe6f5; border: 1px solid #73808c; padding: 10px; }
              button { background: transparent; color: #ff9e40; border: 0; letter-spacing: 0.2em; padding: 8px 0; text-align: left; }
            </style></head>
            <body><form method="post" action="/login">
              <input type="password" name="password" autofocus autocomplete="current-password">
              <button type="submit">ENTER</button>
            </form></body></html>
        """.trimIndent()
    }
}
