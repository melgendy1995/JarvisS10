package com.jarvis.app.system

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Watches media players without a notification listener.
 * Player uid and state are read from [AudioPlaybackConfiguration.toString].
 * The public SDK does not expose getClientUid or getPlayerState.
 */
class PlaybackMonitor(
    context: Context,
    private val onPlaybackStopped: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val packages = appContext.packageManager
    private val handler = Handler(Looper.getMainLooper())
    private val uids = HashMap<String, Int>()
    private var awayPackage: String? = null
    private var sawStarted = false
    private var busy = false
    private var generation = 0
    private var keepGeneration = 0
    private val unparsed = HashSet<String>()
    private var lastAnon = ""

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            consider(configs)
        }
    }

    fun start() {
        audio.registerAudioPlaybackCallback(callback, handler)
    }

    fun setBusy(value: Boolean) {
        busy = value
    }

    fun arm(packageName: String) {
        awayPackage = packageName
        sawStarted = isStarted(packageName)
        generation++
        Log.i(TAG, "playback watch $packageName started=$sawStarted")
    }

    fun disarm() {
        awayPackage = null
        sawStarted = false
        generation++
    }

    /** Re-sends play if speech focus paused a video we just started. */
    fun keepPlaying(packageName: String, windowMs: Long = 12_000) {
        val token = ++keepGeneration
        val until = SystemClock.elapsedRealtime() + windowMs
        fun tick() {
            if (token != keepGeneration || SystemClock.elapsedRealtime() > until) return
            if (isPaused(packageName) && !isStarted(packageName)) {
                Log.i(TAG, "media key play after focus loss package=$packageName")
                dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            handler.postDelayed({ tick() }, 600)
        }
        handler.postDelayed({ tick() }, 600)
    }

    fun cancelKeep() {
        keepGeneration++
    }

    fun isStarted(packageName: String): Boolean = snaps(packageName).any { it.state == STATE_STARTED }

    fun isPaused(packageName: String): Boolean = snaps(packageName).any { it.state == STATE_PAUSED }

    fun dispatch(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0))
        Log.i(TAG, "media key $keyCode")
    }

    fun musicVolume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun musicRange(): Pair<Int, Int> {
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return min to max
    }

    fun adjust(direction: Int) {
        val before = musicVolume()
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        Log.i(TAG, "volume adjust $direction $before -> ${musicVolume()}")
    }

    fun setLevel(level: Int) {
        val before = musicVolume()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        Log.i(TAG, "volume set $before -> ${musicVolume()} target=$level")
    }

    fun setMuted(muted: Boolean) {
        val before = musicVolume()
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        Log.i(TAG, "volume ${if (muted) "mute" else "unmute"} $before -> ${musicVolume()} muted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)}")
    }

    private fun consider(configs: List<AudioPlaybackConfiguration>) {
        val pkg = awayPackage ?: return
        if (busy) return
        val mine = configs.mapNotNull { snap(it) }.filter {
            it.usage == AudioAttributes.USAGE_MEDIA && belongs(it, pkg)
        }
        val started = mine.any { it.state == STATE_STARTED }
        val paused = mine.any { it.state == STATE_PAUSED }
        if (started) {
            sawStarted = true
            return
        }
        if (!sawStarted || paused) return
        val token = generation
        handler.postDelayed({
            if (token != generation || busy || awayPackage != pkg) return@postDelayed
            if (!isStarted(pkg) && !isPaused(pkg)) {
                Log.i(TAG, "playback stopped package=$pkg")
                disarm()
                onPlaybackStopped(pkg)
            }
        }, 4_000)
    }

    private fun snaps(packageName: String): List<Snap> {
        val found = ArrayList<Snap>()
        val anon = ArrayList<Snap>()
        for (config in audio.activePlaybackConfigurations) {
            val parsed = snap(config) ?: continue
            if (parsed.usage != AudioAttributes.USAGE_MEDIA) continue
            if (parsed.uid < 0) anon += parsed
            if (belongs(parsed, packageName)) found += parsed
        }
        if (anon.isNotEmpty()) noteAnon(anon)
        return found
    }

    /**
     * Other apps are anonymized to `u/pid:-1`. A real uid match wins.
     * Anonymized media counts only for the package we just launched.
     */
    private fun belongs(parsed: Snap, packageName: String): Boolean {
        val owner = uid(packageName)
        if (owner != null && parsed.uid == owner) return true
        if (parsed.uid >= 0) return false
        return packageName == awayPackage
    }

    private fun noteAnon(anon: List<Snap>) {
        val line = anon.joinToString(",") { it.state }
        if (line == lastAnon) return
        lastAnon = line
        Log.i(TAG, "anonymized media $line away=$awayPackage")
    }

    /** `u/pid:10226/30612 ... state:started`, or `u/pid:-1/-1` when anonymized. */
    private fun snap(config: AudioPlaybackConfiguration): Snap? {
        val text = config.toString()
        val owner = UID.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val state = STATE.find(text)?.groupValues?.get(1)
        if (owner == null || state == null) {
            if (unparsed.add(text)) Log.i(TAG, "playback config unparsed $text")
            return null
        }
        return Snap(owner, state, config.audioAttributes.usage)
    }

    private fun uid(packageName: String): Int? {
        uids[packageName]?.let { return it }
        return try {
            val uid = packages.getApplicationInfo(packageName, 0).uid
            uids[packageName] = uid
            uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private data class Snap(val uid: Int, val state: String, val usage: Int)

    private companion object {
        const val TAG = "JarvisAppControl"
        const val STATE_STARTED = "started"
        const val STATE_PAUSED = "paused"
        val UID: Regex = Regex("""u/pid:(-?\d+)/""")
        val STATE: Regex = Regex("""\bstate:(\w+)""")
    }
}
