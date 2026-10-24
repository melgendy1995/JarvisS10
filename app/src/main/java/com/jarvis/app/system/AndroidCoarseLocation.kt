package com.jarvis.app.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.jarvis.core.skills.CoarseFix
import com.jarvis.core.skills.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Last coarse fix, or one network fix. Never prompts by itself.
 * [shouldAsk] is true only until the app has shown the dialog once.
 */
class AndroidCoarseLocation(context: Context) : CoarseFix {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val permitted: Boolean
        get() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override val shouldAsk: Boolean
        get() = !permitted && !prefs.getBoolean(ASKED, false)

    override fun markAsked() {
        prefs.edit().putBoolean(ASKED, true).apply()
    }

    override suspend fun read(): GeoPoint? {
        if (!permitted) return null
        val manager = app.getSystemService(LocationManager::class.java) ?: return null
        val last = lastKnown(manager)
        val fresh = last != null && System.currentTimeMillis() - last.time < FRESH_MS
        if (fresh) return last.toPoint()
        val current = withTimeoutOrNull(CURRENT_MS) { currentNetwork(manager) }
        return current?.toPoint() ?: last?.toPoint()
    }

    private fun lastKnown(manager: LocationManager): Location? {
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun currentNetwork(manager: LocationManager): Location? {
        val enabled = runCatching {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (!enabled) return null
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    signal,
                    app.mainExecutor,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun Location.toPoint() = GeoPoint(latitude, longitude)

    private companion object {
        const val PREFS = "jarvis_location"
        const val ASKED = "asked"
        const val FRESH_MS = 15 * 60_000L
        const val CURRENT_MS = 3_000L
    }
}
