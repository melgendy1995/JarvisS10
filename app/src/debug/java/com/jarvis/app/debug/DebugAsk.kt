package com.jarvis.app.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import com.jarvis.app.JarvisApplication
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
 * Force the shelf orb for screenshots:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_STATE -p com.jarvis.app --es state idle
 * state is idle, listening, thinking, speaking, or error.
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
        if (intent.action != ACTION) return
        val question = intent.getStringExtra("q")?.trim().orEmpty()
        val gemini = intent.getBooleanExtra("gemini", true)
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
            try {
                Log.i(TAG, "ask gemini=$gemini via voice loop")
                app.runVoiceUtterance(question)
                Log.i(TAG, "ask finished gemini=$gemini")
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
                inFlight.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.jarvis.app.DEBUG_ASK"
        const val STATE_ACTION = "com.jarvis.app.DEBUG_STATE"
        const val TAG = "JarvisDebug"

        private val inFlight = AtomicBoolean(false)
    }
}
