package com.jarvis.app.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import com.jarvis.app.JarvisApplication
import com.jarvis.core.brain.BrainRequest
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.brain.LiveFollowUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only text entry. While the process is alive:
 * adb shell am broadcast -a com.jarvis.app.DEBUG_ASK --es q "what is the capital of France"
 * Force the chain onto Groq with --ez gemini false.
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
            var follow: LiveFollowUp? = null
            try {
                Log.i(TAG, "ask gemini=$gemini")
                when (val result = app.chain.reply(BrainRequest(question))) {
                    is BrainResult.Answer -> {
                        follow = result.followUp
                        Log.i(TAG, "answer brain=${result.brainId} text=${result.text.take(300)}")
                    }
                    is BrainResult.Failure -> {
                        val error = result.error.javaClass.simpleName
                        Log.i(TAG, "failure brain=${result.brainId} error=$error text=${result.text.take(300)}")
                    }
                }
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
                runCatching { follow?.close() }
                inFlight.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.jarvis.app.DEBUG_ASK"
        const val TAG = "JarvisDebug"

        private val inFlight = AtomicBoolean(false)
    }
}
