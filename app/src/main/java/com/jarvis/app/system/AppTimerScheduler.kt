package com.jarvis.app.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.jarvis.core.skills.TimerHandle
import com.jarvis.core.skills.TimerScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Exact while the kiosk process is alive, and an alarm so a killed process can still announce.
 * Whichever path claims the id first speaks. Cancel marks the id claimed so a late alarm is silent.
 */
class AppTimerScheduler(
    context: Context,
    private val announcements: MutableSharedFlow<String>,
) : TimerScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Running>()
    private val nextCode = AtomicInteger(1)

    override fun schedule(delayMillis: Long, message: String): TimerHandle {
        val id = nextCode.getAndIncrement().toString()
        val requestCode = id.toInt()
        val job = scope.launch {
            delay(delayMillis)
            running.remove(id)
            if (TimerAlarms.claim(id)) {
                deliver(message)
            }
        }
        running[id] = Running(job, requestCode, message)
        scheduleAlarm(id, requestCode, delayMillis, message)
        return TimerHandle { cancel(id) }
    }

    override fun cancelAll(): Int {
        val ids = running.keys.toList()
        ids.forEach { cancel(it) }
        return ids.size
    }

    private fun cancel(id: String) {
        val item = running.remove(id) ?: return
        item.job.cancel()
        TimerAlarms.drop(id)
        alarmManager.cancel(pending(id, item.requestCode, item.message))
    }

    private fun deliver(message: String) {
        val delivered = announcements.subscriptionCount.value > 0 && announcements.tryEmit(message)
        if (!delivered) {
            FallbackSpeaker.speak(appContext, message) {}
        }
    }

    private fun scheduleAlarm(id: String, requestCode: Int, delayMillis: Long, message: String) {
        val intent = pending(id, requestCode, message)
        val triggerAt = System.currentTimeMillis() + delayMillis
        try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    private fun pending(id: String, requestCode: Int, message: String): PendingIntent {
        val intent = Intent(appContext, TimerAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MESSAGE, message)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private class Running(
        val job: Job,
        val requestCode: Int,
        val message: String,
    )

    companion object {
        const val EXTRA_ID = "com.jarvis.app.timer.ID"
        const val EXTRA_MESSAGE = "com.jarvis.app.timer.MESSAGE"
    }
}

internal object TimerAlarms {
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    fun claim(id: String): Boolean = claimed.add(id)

    fun drop(id: String) {
        claimed.add(id)
    }
}
