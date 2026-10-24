@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.jarvis.provider.gemini

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.jarvis.core.brain.BrainError
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.skills.SkillRegistry
import java.time.LocalDate

internal object GeminiModels {
    fun text(
        registry: SkillRegistry,
        system: String = ModelConfig.SYSTEM_PROMPT,
        model: String = ModelConfig.TEXT_MODEL,
    ) = firebase().generativeModel(
        model,
        null,
        null,
        toolList(registry),
        null,
        content("system") { text(system.ifBlank { ModelConfig.SYSTEM_PROMPT }) },
    )

    /**
     * Google Search only. Function calling is a different tool and is not combined
     * with search on this call.
     * Temperature 1.0 is the documented setting for grounding.
     */
    fun grounded(
        system: String,
        model: String = ModelConfig.GROUNDING_MODEL,
    ) = firebase().generativeModel(
        model,
        generationConfig {
            temperature = 1.0f
            maxOutputTokens = 256
        },
        null,
        listOf(Tool.googleSearch()),
        null,
        content("system") { text(system.ifBlank { ModelConfig.SYSTEM_PROMPT }) },
    )

    /** No tools. Used only to rewrite the on-device summary. */
    fun plain(system: String) = firebase().generativeModel(
        ModelConfig.TEXT_MODEL,
        null,
        null,
        emptyList(),
        null,
        content("system") { text(system) },
    )

    fun live(
        registry: SkillRegistry,
        system: String = ModelConfig.SYSTEM_PROMPT,
        model: String = ModelConfig.LIVE_MODEL,
    ) = firebase().liveModel(
        model,
        liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            // SDK writes speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName.
            speechConfig = SpeechConfig(Voice(ModelConfig.LIVE_VOICE))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
            contextWindowCompression = ContextWindowCompressionConfig()
        },
        toolList(registry),
        content("system") { text(system.ifBlank { ModelConfig.SYSTEM_PROMPT }) },
    )

    private fun firebase() = Firebase.ai(backend = GenerativeBackend.googleAI())

    const val BACKEND = "googleAI"

    private fun toolList(registry: SkillRegistry): List<Tool> {
        val declarations = SkillTools.declarations(registry)
        if (declarations.isEmpty()) return emptyList()
        return listOf(Tool.functionDeclarations(declarations))
    }
}

internal fun gate(
    id: String,
    configured: () -> Boolean,
    online: () -> Boolean,
    cooldown: GeminiCooldown,
    time: TimeSource,
    liveCapReached: Boolean = false,
): BrainResult.Failure? {
    if (!configured()) return BrainResult.Failure(id, BrainError.NotConfigured)
    val now = time.now()
    val active = cooldown.active()
    if (active != null) {
        return BrainResult.Failure(
            id,
            BrainError.RateLimited(active.remaining(now), active.isDaily),
        )
    }
    if (liveCapReached) return BrainResult.Failure(id, BrainError.Unavailable)
    if (!online()) return BrainResult.Failure(id, BrainError.Unavailable)
    return null
}

internal fun Throwable.toBrainFailure(
    id: String,
    cooldown: GeminiCooldown,
    time: TimeSource,
): BrainResult.Failure {
    val chain = causeChain()
    if (QuotaSignals.isRateLimit(this)) {
        Log.w(TAG, "gemini rate limited id=$id $chain", this)
        val applied = cooldown.cool(QuotaSignals.messages(this), QuotaSignals.retryAfter(this))
        return BrainResult.Failure(
            id,
            BrainError.RateLimited(applied.remaining(time.now()), applied.isDaily),
        )
    }
    if (QuotaSignals.isNotConfigured(this)) {
        Log.w(TAG, "gemini not configured id=$id $chain", this)
        return BrainResult.Failure(id, BrainError.NotConfigured)
    }
    Log.w(TAG, "gemini failed id=$id $chain", this)
    return BrainResult.Failure(id, BrainError.Unavailable)
}

/** Class and message of this throwable, its causes, and its suppressed throwables. Tokens are stripped. */
internal fun Throwable.causeChain(): String {
    val parts = ArrayList<String>()
    val seen = HashSet<Int>()
    fun walk(error: Throwable) {
        if (!seen.add(System.identityHashCode(error))) return
        val message = redact(error.message.orEmpty()).take(240)
        parts += "${error.javaClass.name}: $message"
        error.cause?.let(::walk)
        error.suppressed.forEach(::walk)
    }
    walk(this)
    return parts.joinToString(" <- ")
}

internal fun Throwable.isModelRejected(): Boolean {
    val text = causeChain()
    return text.contains("not found", ignoreCase = true) ||
        text.contains("unknown model", ignoreCase = true) ||
        text.contains("invalid model", ignoreCase = true) ||
        text.contains("is not supported", ignoreCase = true) ||
        text.contains("unsupported model", ignoreCase = true) ||
        text.contains("was not found", ignoreCase = true) ||
        text.contains("does not exist", ignoreCase = true) ||
        (text.contains("404") && text.contains("model", ignoreCase = true))
}

internal fun redact(message: String): String {
    return message
        .replace(Regex("""gsk_[A-Za-z0-9_\-]+"""), "gsk_[redacted]")
        .replace(Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE), "Bearer [redacted]")
        .replace(Regex("""ya29\.[A-Za-z0-9_\-.]+"""), "ya29.[redacted]")
}

internal const val TAG = "JarvisGemini"

class NetworkStatus(context: Context) {
    private val app = context.applicationContext

    fun isOnline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class SharedPrefsUsageStore(context: Context) : UsageStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Usage? {
        val day = prefs.getString(DAY, null) ?: return null
        return Usage(
            day = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null,
            liveTurns = prefs.getInt(TURNS, 0),
            liveSeconds = prefs.getLong(SECONDS, 0),
            textRequests = prefs.getInt(TEXT, 0),
            groundedRequests = prefs.getInt(GROUNDED, 0),
        )
    }

    override fun write(usage: Usage) {
        prefs.edit()
            .putString(DAY, usage.day.toString())
            .putInt(TURNS, usage.liveTurns)
            .putLong(SECONDS, usage.liveSeconds)
            .putInt(TEXT, usage.textRequests)
            .putInt(GROUNDED, usage.groundedRequests)
            .commit()
    }

    private companion object {
        const val PREFS = "jarvis_gemini_usage"
        const val DAY = "day"
        const val TURNS = "live_turns"
        const val SECONDS = "live_seconds"
        const val TEXT = "text_requests"
        const val GROUNDED = "grounded_requests"
    }
}

class SharedPrefsCooldownStore(context: Context) : CooldownStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Cooldown? {
        if (!prefs.contains(UNTIL)) return null
        return Cooldown(
            until = java.time.Instant.ofEpochMilli(prefs.getLong(UNTIL, 0L)),
            isDaily = prefs.getBoolean(DAILY, false),
        )
    }

    override fun write(cooldown: Cooldown) {
        prefs.edit()
            .putLong(UNTIL, cooldown.until.toEpochMilli())
            .putBoolean(DAILY, cooldown.isDaily)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS = "jarvis_gemini_cooldown"
        const val UNTIL = "until"
        const val DAILY = "daily"
    }
}

object GeminiFirebase {
    @Volatile
    private var ready = false

    fun isReady(): Boolean = ready

    /** Returns false when there is no Firebase config. The app keeps running. */
    fun install(context: Context): Boolean {
        if (ready) return true
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return false
            BuildAppCheckInstaller().install(app)
            ready = true
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Firebase is not configured", error)
            false
        }
    }
}
