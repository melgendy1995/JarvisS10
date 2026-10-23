package com.jarvis.app

import android.app.Application
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        timers = AppTimerScheduler(this, announcements)
        val registry = SkillRegistry()
        registry.register(TimeSkill())
        registry.register(TimerSkill(timers))
        registry.register(FlashlightSkill(CameraTorchController(this)))
        router = CommandRouter(registry)
        val firebaseReady = GeminiFirebase.install(this)
        val usage = UsageCounter(SystemTimeSource, SharedPrefsUsageStore(this))
        val cooldown = GeminiCooldown(SystemTimeSource, SharedPrefsCooldownStore(this))
        val network = NetworkStatus(this)
        val configured = { firebaseReady || GeminiFirebase.isReady() }
        chain = BrainChain(
            router,
            listOf(
                GeminiLiveBrain(
                    scope = appScope,
                    configured = configured,
                    online = network::isOnline,
                    cooldown = cooldown,
                    usage = usage,
                    registry = registry,
                ),
                GeminiTextBrain(
                    configured = configured,
                    online = network::isOnline,
                    cooldown = cooldown,
                    usage = usage,
                    registry = registry,
                ),
            ),
        )
    }
}
