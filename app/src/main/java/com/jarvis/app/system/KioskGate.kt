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
        if (!leftLockTask) return
        val host = activity ?: return
        val reentered = runCatching { host.startLockTask() }.isSuccess
        Log.i(TAG, "lock-task re-entered success=$reentered")
        if (reentered) leftLockTask = false
    }

    fun returnToJarvis(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        Log.i(TAG, "lock-task return to jarvis left=$leftLockTask")
        context.startActivity(intent)
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
