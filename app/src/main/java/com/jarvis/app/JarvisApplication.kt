package com.jarvis.app

import android.app.Application
import com.jarvis.app.system.AppTimerScheduler
import com.jarvis.app.system.CameraTorchController
import com.jarvis.core.CommandRouter
import com.jarvis.core.skills.FlashlightSkill
import com.jarvis.core.skills.SkillRegistry
import com.jarvis.core.skills.TimeSkill
import com.jarvis.core.skills.TimerSkill
import kotlinx.coroutines.flow.MutableSharedFlow

class JarvisApplication : Application() {
    val announcements = MutableSharedFlow<String>(extraBufferCapacity = 8)

    lateinit var router: CommandRouter
        private set

    lateinit var timers: AppTimerScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        timers = AppTimerScheduler(this, announcements)
        val registry = SkillRegistry()
        registry.register(TimeSkill())
        registry.register(TimerSkill(timers))
        registry.register(FlashlightSkill(CameraTorchController(this)))
        router = CommandRouter(registry)
    }
}
