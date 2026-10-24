package com.jarvis.app.system

import com.jarvis.core.skills.OpenMeteo
import com.jarvis.core.skills.WeatherClient
import com.jarvis.core.skills.WeatherReading
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Open-Meteo forecast. No key. Non-commercial free tier. */
class OpenMeteoClient : WeatherClient {
    override suspend fun fetch(latitude: Double, longitude: Double): WeatherReading? {
        return withContext(Dispatchers.IO) {
            val connection = URL(OpenMeteo.url(latitude, longitude)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "JarvisS10 weather (open-meteo)")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = connection.inputStream.use { input ->
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
                OpenMeteo.parse(body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 65_536
    }
}
