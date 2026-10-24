package com.jarvis.app.system

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Camera password in the Android keystore. Never log the value, never put it in a URL.
 * A keystore failure leaves the camera unconfigured instead of falling back to plaintext.
 */
class CameraPasswordStore(context: Context) {
    private val prefs: SharedPreferences? = open(context.applicationContext)

    fun get(): String {
        val stored = prefs ?: return ""
        return try {
            stored.getString(KEY, "").orEmpty().trim()
        } catch (error: Throwable) {
            Log.w(TAG, "camera password read failed: ${error.javaClass.simpleName}")
            ""
        }
    }

    fun isOpen(): Boolean = prefs != null

    fun set(password: String): Boolean {
        val editor = prefs?.edit() ?: return false
        val clean = password.trim().replace("\r", "").replace("\n", "")
        if (clean.length > MAX) return false
        if (clean.isEmpty()) editor.remove(KEY) else editor.putString(KEY, clean)
        return try {
            editor.commit()
        } catch (error: Throwable) {
            Log.w(TAG, "camera password write failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun open(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "camera keystore unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "JarvisCamera"
        const val FILE = "jarvis_camera_password"
        const val KEY = "password"
        const val MAX = 200
    }
}

/** Port is not a secret. Default 8088. */
class CameraPortStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): Int {
        val stored = prefs.getInt(KEY, DEFAULT)
        return stored.takeIf { it in MIN..MAX } ?: DEFAULT
    }

    fun set(port: Int): Boolean {
        if (port !in MIN..MAX) return false
        return prefs.edit().putInt(KEY, port).commit()
    }

    companion object {
        const val DEFAULT = 8088
        const val MIN = 1024
        const val MAX = 65535
        private const val FILE = "jarvis_camera"
        private const val KEY = "port"
    }
}
