package com.jarvis.app.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.jarvis.app.JarvisApplication
import com.jarvis.app.system.CameraStreamService
import com.jarvis.app.system.ListenForegroundService
import com.jarvis.app.system.VoiceLanguageStore
import com.jarvis.audio.wake.WavPcm
import com.jarvis.provider.groq.ArabicRecheckPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only text entry. While the activity's voice loop is up:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_ASK --es q "what is the capital of France"
 * Force the chain onto Groq with --ez gemini false.
 * [q] is injected as the recognized utterance, then the same voice-loop path speaks the answer.
 *
 * Set the voice-language setting without the UI:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_VOICE_LANG --es v auto
 * [v] is auto, en, or ar; anything else is ignored by the store.
 *
 * Replay a 16 kHz mono 16-bit wav from the app's files dir through Whisper, then
 * hand the transcript to the voice loop as if it had been spoken:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_STT_FILE --es path arabic.wav
 * Files under getExternalFilesDir also work, so a terminal push lands in
 * files/arabic.wav or files/Download.
 *
 * Force the shelf orb for screenshots:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_STATE -p com.jarvis.app --es state idle
 * state is idle, listening, thinking, speaking, or error.
 *
 * Camera toggle for stress loops, the same path as the voice camera on/off:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_CAMERA --es op start
 * adb shell am broadcast -a com.jarvis.app.DEBUG_CAMERA --es op stop
 * op is start/on, stop/off, or switch; start|stop are aliases for on|off.
 */
object GeminiDebug {
    @Volatile
    var enabled: Boolean = true
}

private val askScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Manifest receiver only. A dynamic registration delivered the same broadcast twice. */
fun registerDebugAsk(app: Application) = Unit

class DebugAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STATE_ACTION) {
            val name = intent.getStringExtra("state")
            if (!OrbDebug.force(name)) Log.w(TAG, "bad state")
            return
        }
        if (intent.action == WAKE_ACTION) {
            val path = intent.getStringExtra("path")?.trim().orEmpty()
            if (path.isEmpty()) {
                Log.w(TAG, "missing path")
                return
            }
            Log.i(TAG, "debug wake $path")
            ListenForegroundService.scoreWav(context, path, emit = true)
            return
        }
        if (intent.action == VOICE_LANG_ACTION) {
            val value = intent.getStringExtra("v")?.trim().orEmpty()
            VoiceLanguageStore.set(context.applicationContext, value)
            Log.i(TAG, "debug voice lang=$value")
            return
        }
        if (intent.action == STT_FILE_ACTION) {
            runSttFile(context, intent)
            return
        }
        if (intent.action == CAMERA_PASSWORD_ACTION) {
            storeCameraPassword(context, intent)
            return
        }
        if (intent.action == CAMERA_ACTION) {
            runCamera(context, intent)
            return
        }
        if (intent.action != ACTION) return
        val question = intent.getStringExtra("q")?.trim().orEmpty()
        val gemini = intent.getBooleanExtra("gemini", true)
        val browser = intent.getBooleanExtra("browser", true)
        if (question.isEmpty()) {
            Log.w(TAG, "missing q")
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            Log.i(TAG, "ask already running")
            return
        }
        val app = context.applicationContext as? JarvisApplication
        if (app == null) {
            inFlight.set(false)
            Log.w(TAG, "application is not ready")
            return
        }
        val pending = goAsync()
        askScope.launch {
            val previous = GeminiDebug.enabled
            GeminiDebug.enabled = gemini
            app.setGroqBrowserEnabled(browser)
            try {
                Log.i(TAG, "ask gemini=$gemini browser=$browser via voice loop")
                app.runVoiceUtterance(question)
                Log.i(TAG, "ask finished gemini=$gemini browser=$browser")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                val where = cancelled.stackTrace.take(6).joinToString(" | ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                Log.w(TAG, "ask cancelled: ${cancelled.javaClass.simpleName} $where")
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "debug ask failed", error)
            } finally {
                GeminiDebug.enabled = previous
                app.setGroqBrowserEnabled(true)
                inFlight.set(false)
                pending.finish()
            }
        }
    }

    private fun storeCameraPassword(context: Context, intent: Intent) {
        val app = context.applicationContext as? JarvisApplication
        if (app == null) {
            Log.w(TAG, "application is not ready")
            return
        }
        if (intent.getBooleanExtra("clear", false)) {
            val ok = app.saveCameraPassword("")
            Log.i(TAG, if (ok) "camera password cleared" else "camera password clear failed")
            return
        }
        val password = intent.getStringExtra("p").orEmpty()
        if (password.length < 8 || password.length > 128) {
            Log.w(TAG, "camera password rejected")
            return
        }
        val ok = app.saveCameraPassword(password)
        Log.i(TAG, if (ok) "camera password stored" else "camera password store failed")
    }

    private fun runCamera(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op")?.trim().orEmpty()
        val mapped = when (op) {
            "on", "start" -> "on"
            "off", "stop" -> "off"
            "switch" -> "switch"
            else -> null
        }
        if (mapped == null) {
            Log.w(TAG, "camera op rejected")
            return
        }
        val pending = goAsync()
        askScope.launch {
            try {
                val outcome = when (mapped) {
                    "on" -> CameraStreamService.start(context)
                    "off" -> CameraStreamService.stop(context)
                    else -> CameraStreamService.switch(context)
                }
                Log.i(TAG, "camera op=$op outcome=$outcome")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "camera op failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun runSttFile(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")?.trim().orEmpty()
        if (path.isEmpty()) {
            Log.w(TAG, "missing path")
            return
        }
        val app = context.applicationContext as? JarvisApplication
        if (app == null) {
            Log.w(TAG, "application is not ready")
            return
        }
        if (!sttInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "debug stt already running")
            return
        }
        val pending = goAsync()
        askScope.launch {
            try {
                val file = resolveWav(context, path)
                if (file == null) {
                    Log.w(TAG, "debug wav missing path=$path")
                    return@launch
                }
                val pcm = runCatching { wavMonoBytes(file.readBytes()) }.getOrNull()
                if (pcm == null) {
                    Log.w(TAG, "debug wav unreadable path=$path")
                    return@launch
                }
                Log.i(TAG, "debug stt bytes=${pcm.size}")
                val text = app.whisper.transcribe(pcm)?.trim().orEmpty()
                Log.i(TAG, "debug stt chars=${text.length} arabic=${ArabicRecheckPolicy.hasArabic(text)}")
                if (text.isEmpty()) return@launch
                app.runVoiceUtterance(text)
                Log.i(TAG, "debug stt delivered via voice loop")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "debug stt failed: ${error.javaClass.simpleName}")
            } finally {
                sttInFlight.set(false)
                pending.finish()
            }
        }
    }

    private fun resolveWav(context: Context, path: String): File? {
        val flat = path.trimStart('/')
        val internal = File(context.filesDir, flat)
        if (internal.isFile) return internal
        val direct = File(path)
        if (direct.isFile) return direct
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            val candidate = File(external, flat)
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** 16 kHz mono shorts from WavPcm, back into little-endian L16 PCM for Whisper. */
    private fun wavMonoBytes(bytes: ByteArray): ByteArray? {
        val clip = WavPcm.read(bytes) ?: return null
        val out = ByteArray(clip.samples.size * 2)
        for (i in clip.samples.indices) {
            val value = clip.samples[i].toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    companion object {
        const val ACTION = "com.jarvis.app.DEBUG_ASK"
        const val STATE_ACTION = "com.jarvis.app.DEBUG_STATE"
        const val WAKE_ACTION = "com.jarvis.app.DEBUG_WAKE"
        const val VOICE_LANG_ACTION = "com.jarvis.app.DEBUG_VOICE_LANG"
        const val STT_FILE_ACTION = "com.jarvis.app.DEBUG_STT_FILE"
        const val CAMERA_PASSWORD_ACTION = "com.jarvis.app.DEBUG_CAMERA_PASSWORD"
        const val CAMERA_ACTION = "com.jarvis.app.DEBUG_CAMERA"
        const val TAG = "JarvisDebug"

        private val inFlight = AtomicBoolean(false)
        private val sttInFlight = AtomicBoolean(false)
    }
}
