package com.jarvis.core.camera

import java.security.MessageDigest
import java.util.Base64

/** SHA-256 both sides so the compare stays constant-time for any length. */
fun secretEquals(left: String, right: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val a = digest.digest(left.toByteArray(Charsets.UTF_8))
    val b = MessageDigest.getInstance("SHA-256").digest(right.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(a, b)
}

/**
 * Password half of an HTTP Basic header. The username is ignored.
 * Returns null when the header is missing or not Basic.
 */
fun basicPassword(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val prefix = "basic "
    if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
    val token = header.substring(prefix.length).trim()
    if (token.isEmpty() || token.length > 512) return null
    val decoded = try {
        String(Base64.getMimeDecoder().decode(token), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val colon = decoded.indexOf(':')
    val password = if (colon < 0) decoded else decoded.substring(colon + 1)
    if (password.length > 256) return null
    return password
}

fun cookieValue(header: String?, name: String): String? {
    if (header.isNullOrBlank()) return null
    val needle = "$name="
    for (part in header.split(';')) {
        val piece = part.trim()
        if (piece.startsWith(needle)) return piece.substring(needle.length).trim()
    }
    return null
}

/** True when the presented password matches. A blank expected password never matches. */
fun passwordAccepted(presented: String?, expected: String): Boolean {
    if (expected.isEmpty() || presented == null) return false
    return secretEquals(presented, expected)
}

fun formPassword(body: String): String? {
    for (part in body.split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = urlDecode(part.substring(0, eq))
        if (key == "password") return urlDecode(part.substring(eq + 1)).take(256)
    }
    return null
}

private fun urlDecode(value: String): String {
    return try {
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }
}
