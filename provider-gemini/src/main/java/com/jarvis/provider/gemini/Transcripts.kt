package com.jarvis.provider.gemini

/** Live transcription chunks are sometimes cumulative and sometimes a new piece. */
object Transcripts {
    fun append(current: StringBuilder, chunk: String?) {
        val piece = chunk?.trim().orEmpty()
        if (piece.isEmpty()) return
        val existing = current.toString()
        if (existing.isEmpty()) {
            current.append(piece)
            return
        }
        if (piece.startsWith(existing)) {
            current.clear()
            current.append(piece)
            return
        }
        if (existing.endsWith(piece)) return
        if (!existing.endsWith(" ") && !piece.startsWith(" ")) current.append(' ')
        current.append(piece)
    }
}
