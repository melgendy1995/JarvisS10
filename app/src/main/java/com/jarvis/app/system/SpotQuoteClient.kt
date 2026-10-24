package com.jarvis.app.system

import com.jarvis.core.skills.MetalsQuote
import com.jarvis.core.skills.SpotFeed
import com.jarvis.core.skills.SpotTick
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gold and silver from Gold API, riyals from ExchangeRate-API. Neither needs a key.
 * https://api.gold-api.com/price/XAU
 * https://open.er-api.com/v6/latest/USD
 */
class SpotQuoteClient : SpotFeed {
    override suspend fun metal(symbol: String): SpotTick? {
        val code = if (symbol == "XAG") "XAG" else "XAU"
        val body = get("https://api.gold-api.com/price/$code") ?: return null
        return MetalsQuote.parseGoldApi(body, code)
    }

    override suspend fun usdToSar(): SpotTick? {
        val body = get("https://open.er-api.com/v6/latest/USD") ?: return null
        return MetalsQuote.parseUsdSar(body)
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "JarvisS10 spot")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 65_536
    }
}
