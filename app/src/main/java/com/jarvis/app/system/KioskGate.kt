package com.jarvis.app.system

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.app.MainActivity

/**
 * Device-owner phones allowlist the target package and stay in lock task.
 * Every other phone leaves lock task only when it is actually on, then
 * [MainActivity] re-enters it when Jarvis comes back.
 */
object KioskGate {
    private var activity: MainActivity? = null
    private var leftLockTask = false
    private var lastReturnMs = 0L

    /** True between MainActivity.onResume and onPause. */
    @Volatile
    var jarvisResumed = false
        private set

    fun setResumed(resumed: Boolean) {
        jarvisResumed = resumed
    }

    fun attach(host: MainActivity) {
        activity = host
    }

    fun detach(host: MainActivity) {
        if (activity === host) activity = null
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun prepareToLeave(context: Context, targetPackage: String) {
        if (allowlist(context, targetPackage)) return
        val state = lockState(context)
        if (state == ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.i(TAG, "lock-task none before launch $targetPackage")
            return
        }
        val host = activity
        if (host == null) {
            Log.i(TAG, "lock-task active state=$state but Jarvis is not in front")
            return
        }
        val stopped = runCatching { host.stopLockTask() }.isSuccess
        leftLockTask = stopped
        Log.i(TAG, "lock-task left state=$state stopped=$stopped for $targetPackage")
    }

    fun onJarvisVisible() {
        val host = activity ?: return
        if (leftLockTask) {
            val reentered = runCatching { host.startLockTask() }.isSuccess
            val state = lockState(host)
            Log.i(TAG, "lock-task re-entered success=$reentered state=$state")
            // Keep the flag until the state really changes, so a focus retry can pin.
            if (reentered && state != ActivityManager.LOCK_TASK_MODE_NONE) leftLockTask = false
            return
        }
        // A device owner is always allowed back into lock task: after a reboot or a
        // manual exit, being in front again is enough to re-enter. Non-owner phones
        // (screen pinning) are left alone here so a phone returned to HOME stays out.
        if (isDeviceOwner(host) && lockState(host) == ActivityManager.LOCK_TASK_MODE_NONE) {
            val entered = runCatching { host.startLockTask() }.isSuccess
            Log.i(TAG, "lock-task entered state=none owner=true success=$entered")
        }
    }

    /**
     * Reorders Jarvis's own task to the front. The other app is not finished, so a
     * player keeps playing in the background. Started from the accessibility service
     * when it is bound (allowed to start activities from the background), else from
     * [context]. Skipped when Jarvis is already in front or a return just happened,
     * so it can never loop.
     */
    /** Retry the pin once the window has focus; a request during the transition is dropped. */
    fun onJarvisFocused() {
        val host = activity ?: return
        if (!leftLockTask) return
        if (lockState(host) != ActivityManager.LOCK_TASK_MODE_NONE) {
            leftLockTask = false
            return
        }
        val ok = runCatching { host.startLockTask() }.isSuccess
        Log.i(TAG, "lock-task focus retry success=$ok state=${lockState(host)}")
        leftLockTask = false
    }

    fun returnToJarvis(context: Context, reason: String = "auto"): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!ForegroundReturn.allowReturn(jarvisResumed, now, lastReturnMs)) {
            Log.i(TAG, "return to jarvis skipped reason=$reason resumed=$jarvisResumed")
            return false
        }
        lastReturnMs = now
        val starter: Context = JarvisAccessibilityService.instance ?: context.applicationContext
        val intent = Intent(starter, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        Log.i(TAG, "lock-task return to jarvis reason=$reason left=$leftLockTask via=${starter.javaClass.simpleName}")
        return try {
            starter.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.i(TAG, "return to jarvis failed", e)
            false
        }
    }

    private fun allowlist(context: Context, targetPackage: String): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = ComponentName(context, JarvisDeviceAdmin::class.java)
        val packages = dpm.getLockTaskPackages(admin).toMutableSet()
        packages += context.packageName
        packages += targetPackage
        dpm.setLockTaskPackages(admin, packages.toTypedArray())
        Log.i(TAG, "lock-task allowlist $targetPackage")
        return true
    }

    private fun lockState(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return ActivityManager.LOCK_TASK_MODE_NONE
        return manager.lockTaskModeState
    }

    private const val TAG = "JarvisAppControl"
}
