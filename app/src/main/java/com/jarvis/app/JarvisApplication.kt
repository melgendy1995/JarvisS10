package com.jarvis.app

import android.app.Application
import android.util.Log
import com.jarvis.app.system.AppTimerScheduler
import com.jarvis.app.system.CameraTorchController
import com.jarvis.core.CommandRouter
import com.jarvis.core.brain.BrainChain
import com.jarvis.core.brain.BrainResult
import com.jarvis.core.skills.FlashlightSkill
import com.jarvis.core.skills.MemorySkill
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.TimeSkill
import com.jarvis.core.skills.TimerSkill
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.MemoryLimits
import com.jarvis.data.MemoryRepository
import com.jarvis.provider.gemini.GeminiCooldown
import com.jarvis.provider.gemini.GeminiFirebase
import com.jarvis.provider.gemini.GeminiLiveBrain
import com.jarvis.provider.gemini.GeminiTextBrain
import com.jarvis.provider.gemini.NetworkStatus
import com.jarvis.provider.gemini.SharedPrefsCooldownStore
import com.jarvis.provider.gemini.SharedPrefsUsageStore
import com.jarvis.provider.gemini.SystemTimeSource
import com.jarvis.provider.gemini.UsageCounter
import com.jarvis.app.debug.GeminiDebug
import com.jarvis.app.debug.registerDebugAsk
import com.jarvis.provider.groq.BuildConfig as GroqBuildConfig
import com.jarvis.provider.groq.GroqBrain
import com.jarvis.provider.groq.GroqProxy
import com.jarvis.provider.groq.GroqRotation
import com.jarvis.provider.groq.GroqWhisperStt
import com.jarvis.provider.groq.KeystoreBearerStore
import com.jarvis.provider.groq.SharedPrefsGroqStore
import com.jarvis.provider.groq.SystemGroqClock
import com.jarvis.provider.groq.groqTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class JarvisApplication : Application() {
    val announcements = MutableSharedFlow<String>(extraBufferCapacity = 8)

    lateinit var router: CommandRouter
        private set

    lateinit var chain: BrainChain
        private set

    lateinit var memory: MemoryRepository
        private set

    lateinit var timers: AppTimerScheduler
        private set

    lateinit var whisper: GroqWhisperStt
        private set

    private lateinit var bearerStore: KeystoreBearerStore
    private lateinit var groqBrain: GroqBrain
    private lateinit var geminiText: GeminiTextBrain
    private lateinit var network: NetworkStatus
    private lateinit var geminiCooldown: GeminiCooldown
    private var firebaseReady = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Outlives the recognizer and a Live socket. The voice loop and DEBUG_ASK both
     * run here, and only a new hold or clearing the ViewModel cancels an utterance.
     */
    val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var onVoiceUtterance: (suspend (String) -> Unit)? = null

    /** Same entry the recognizer uses, including speech. Logs and returns when the activity is down. */
    suspend fun runVoiceUtterance(text: String) {
        val handler = onVoiceUtterance
        if (handler == null) {
            Log.w("JarvisVoice", "voice loop is not running")
            return
        }
        val job = voiceScope.launch { handler(text) }
        job.join()
    }

    override fun onCreate() {
        super.onCreate()
        timers = AppTimerScheduler(this, announcements)
        memory = MemoryRepository(JarvisDatabase.create(this).memoryDao())
        val registry = SkillRegistry()
        registry.register(TimeSkill())
        registry.register(TimerSkill(timers))
        registry.register(FlashlightSkill(CameraTorchController(this)))
        registry.register(MemorySkill(RoomMemory(memory)))
        router = CommandRouter(registry)
        firebaseReady = GeminiFirebase.install(this)
        val usage = UsageCounter(SystemTimeSource, SharedPrefsUsageStore(this))
        geminiCooldown = GeminiCooldown(SystemTimeSource, SharedPrefsCooldownStore(this))
        network = NetworkStatus(this)
        bearerStore = KeystoreBearerStore(this)
        val transport = groqTransport(
            proxyUrl = { GroqBuildConfig.GROQ_PROXY_URL },
            bearer = { bearerStore.get() },
        )
        val groqLog: (String) -> Unit = { message -> Log.i("JarvisGroq", message) }
        groqBrain = GroqBrain(
            proxyUrl = { GroqBuildConfig.GROQ_PROXY_URL },
            bearer = { bearerStore.get() },
            online = network::isOnline,
            rotation = GroqRotation(SystemGroqClock, SharedPrefsGroqStore(this)),
            registry = registry,
            transport = transport,
            log = groqLog,
        )
        whisper = GroqWhisperStt(
            proxyUrl = { GroqBuildConfig.GROQ_PROXY_URL },
            bearer = { bearerStore.get() },
            transport = transport,
            log = groqLog,
        )
        registerDebugAsk(this)
        val configured = { GeminiDebug.enabled && (firebaseReady || GeminiFirebase.isReady()) }
        geminiText = GeminiTextBrain(
            configured = configured,
            online = network::isOnline,
            cooldown = geminiCooldown,
            usage = usage,
            registry = registry,
        )
        chain = BrainChain(
            router,
            listOf(
                GeminiLiveBrain(
                    scope = appScope,
                    configured = configured,
                    online = network::isOnline,
                    cooldown = geminiCooldown,
                    usage = usage,
                    registry = registry,
                ),
                geminiText,
                groqBrain,
            ),
        ) { line -> Log.i("JarvisBrain", line) }
        Log.i(
            "JarvisGroq",
            "ready proxy=${groqProxyConfigured()} bearer=${bearerStore.get().isNotBlank()} keystore=${bearerStore.isOpen()}",
        )
    }

    /**
     * Rewrites a summary with whichever configured text brain can answer.
     * Live audio is not used. A miss leaves the local truncation in Room.
     */
    suspend fun rewriteSummary(prompt: String): String? {
        if (prompt.isBlank()) return null
        return try {
            summaryText(geminiText.summarize(prompt)) ?: summaryText(groqBrain.summarize(prompt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private fun summaryText(result: BrainResult): String? {
        val answer = result as? BrainResult.Answer ?: return null
        return answer.text.trim().take(MemoryLimits.SUMMARY_CHARS).takeIf { it.isNotEmpty() }
    }

    fun groqProxyConfigured(): Boolean = GroqProxy.normalize(GroqBuildConfig.GROQ_PROXY_URL) != null

    fun groqBearerSaved(): Boolean = bearerStore.get().isNotBlank()

    fun groqKeystoreOpen(): Boolean = bearerStore.isOpen()

    fun saveGroqBearer(token: String): Boolean = bearerStore.set(token)

    /** Gemini or Groq can still answer, so a failed on-device transcript is worth a Whisper call. */
    fun cloudBrainReachable(): Boolean {
        if (!network.isOnline()) return false
        val geminiReady = (firebaseReady || GeminiFirebase.isReady()) && !geminiCooldown.isCooling()
        return geminiReady || groqBrain.hasCapacity()
    }
}
