package com.jarvis.core.camera

/** Enough JSON for one SDP offer or answer. No general parser. */
object JsonText {
    fun stringField(json: String, name: String): String? {
        val key = "\"$name\""
        var search = 0
        while (search < json.length) {
            val keyAt = json.indexOf(key, search)
            if (keyAt < 0) return null
            var i = keyAt + key.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') {
                search = keyAt + key.length
                continue
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') return null
            return readString(json, i + 1)
        }
        return null
    }

    fun answer(sdp: String): String {
        return "{\"type\":\"answer\",\"sdp\":${quote(sdp)}}"
    }

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun readString(json: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            val ch = json[i]
            if (ch == '"') return sb.toString()
            if (ch != '\\') {
                sb.append(ch)
                i++
                continue
            }
            if (i + 1 >= json.length) return null
            when (val esc = json[i + 1]) {
                '"', '\\', '/' -> sb.append(esc)
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 5 >= json.length) return null
                    val hex = json.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    sb.append(code.toChar())
                    i += 4
                }
                else -> return null
            }
            i += 2
        }
        return null
    }
}

fun normalizeSdp(sdp: String): String {
    val lines = sdp.replace("\r\n", "\n").replace('\r', '\n').trim().lines()
    if (lines.isEmpty() || lines.first().isBlank()) return ""
    return lines.joinToString("\r\n") + "\r\n"
}
