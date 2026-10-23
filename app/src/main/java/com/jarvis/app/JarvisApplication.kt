package com.jarvis.app

import android.app.Application
import android.util.Log
import com.jarvis.app.system.AppTimerScheduler
import com.jarvis.app.system.CameraTorchController
import com.jarvis.core.CommandRouter
import com.jarvis.core.brain.BrainChain
import com.jarvis.core.skills.FlashlightSkill
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.TimeSkill
import com.jarvis.core.skills.TimerSkill
import com.jarvis.provider.gemini.GeminiCooldown
import com.jarvis.provider.gemini.GeminiFirebase
import com.jarvis.provider.gemini.GeminiLiveBrain
import com.jarvis.provider.gemini.GeminiTextBrain
import com.jarvis.provider.gemini.NetworkStatus
import com.jarvis.provider.gemini.SharedPrefsCooldownStore
import com.jarvis.provider.gemini.SharedPrefsUsageStore
import com.jarvis.provider.gemini.SystemTimeSource
import com.jarvis.provider.gemini.UsageCounter
import com.jarvis.provider.groq.BuildConfig as GroqBuildConfig
import com.jarvis.provider.groq.GroqBrain
import com.jarvis.provider.groq.GroqProxy
import com.jarvis.provider.groq.GroqRotation
import com.jarvis.provider.groq.GroqWhisperStt
import com.jarvis.provider.groq.KeystoreBearerStore
import com.jarvis.provider.groq.SharedPrefsGroqStore
import com.jarvis.provider.groq.SystemGroqClock
import com.jarvis.provider.groq.groqTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

class JarvisApplication : Application() {
    val announcements = MutableSharedFlow<String>(extraBufferCapacity = 8)

    lateinit var router: CommandRouter
        private set

    lateinit var chain: BrainChain
        private set

    lateinit var timers: AppTimerScheduler
        private set

    lateinit var whisper: GroqWhisperStt
        private set

    private lateinit var bearerStore: KeystoreBearerStore
    private lateinit var groqBrain: GroqBrain
    private lateinit var network: NetworkStatus
    private lateinit var geminiCooldown: GeminiCooldown
    private var firebaseReady = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        timers = AppTimerScheduler(this, announcements)
        val registry = SkillRegistry()
        registry.register(TimeSkill())
        registry.register(TimerSkill(timers))
        registry.register(FlashlightSkill(CameraTorchController(this)))
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
        val configured = { firebaseReady || GeminiFirebase.isReady() }
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
                GeminiTextBrain(
                    configured = configured,
                    online = network::isOnline,
                    cooldown = geminiCooldown,
                    usage = usage,
                    registry = registry,
                ),
                groqBrain,
            ),
        )
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
