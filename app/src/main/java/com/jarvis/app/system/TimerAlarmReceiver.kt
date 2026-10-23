package com.jarvis.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.app.JarvisApplication

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AppTimerScheduler.EXTRA_ID) ?: return
        val message = intent.getStringExtra(AppTimerScheduler.EXTRA_MESSAGE) ?: "Timer done."
        val pending = goAsync()
        if (!TimerAlarms.claim(id)) {
            pending.finish()
            return
        }
        val app = context.applicationContext as? JarvisApplication
        val hub = app?.announcements
        val delivered = hub != null && hub.subscriptionCount.value > 0 && hub.tryEmit(message)
        if (delivered) {
            pending.finish()
        } else {
            FallbackSpeaker.speak(context.applicationContext, message) {
                pending.finish()
            }
        }
    }
}
