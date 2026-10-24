package com.jarvis.app.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.core.skills.UiNode
import com.jarvis.core.skills.YouTubeUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Scoped to YouTube. Taps the first real search result, the skip-ad button,
 * and the in-player pause, play, and next controls when they are on screen.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Flags and canPerformGestures come from the XML. Writing serviceInfo
        // here clears the gesture capability on this phone.
        val caps = serviceInfo?.capabilities ?: 0
        Log.i(TAG, "service connected capabilities=$caps")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun snapshot(): List<UiNode> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (window in windows) {
            val root = window.root ?: continue
            roots += root
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots += it }
        }
        val nodes = ArrayList<UiNode>()
        for (root in roots) {
            collect(root, nodes)
            root.recycle()
        }
        return nodes
    }

    fun screenWidth(): Int = displaySpan(width = true)

    fun screenHeight(): Int = displaySpan(width = false)

    /**
     * One tap. Large rows (search results) use a gesture on the thumbnail,
     * because a second click on the watch page pauses the video.
     * Small controls try [AccessibilityNodeInfo.ACTION_CLICK] and fall back
     * to a gesture when YouTube does not accept the action.
     */
    suspend fun tap(node: UiNode): Boolean {
        val small = node.height < 420
        if (small && performClick(node)) {
            Log.i(TAG, "tap click top=${node.top} id=${node.viewId.substringAfterLast('/')} desc=${node.desc.take(80)}")
            return true
        }
        val point = tapPoint(node)
        val gestured = gestureTap(point.first, point.second)
        if (gestured) {
            Log.i(
                TAG,
                "gesture tap x=${point.first} y=${point.second} h=${node.height} desc=${node.desc.take(80)}",
            )
            return true
        }
        val clicked = performClick(node)
        Log.i(
            TAG,
            "tap fallback click=$clicked x=${point.first} y=${point.second} h=${node.height} desc=${node.desc.take(80)}",
        )
        return clicked
    }

    suspend fun scrollResults(): Boolean {
        val scrolled = scrollForward()
        if (scrolled) {
            Log.i(TAG, "scroll results")
            return true
        }
        val width = screenWidth()
        val height = screenHeight()
        val swiped = gestureSwipe(width / 2, (height * 0.72f).toInt(), width / 2, (height * 0.38f).toInt())
        Log.i(TAG, "scroll gesture ok=$swiped")
        return swiped
    }

    suspend fun skipAd(timeoutMs: Long = 8_000): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < timeoutMs) {
            val button = YouTubeUi.skipButton(snapshot())
            if (button != null) {
                val tapped = tap(button)
                Log.i(TAG, "skip ad tapped=$tapped")
                if (tapped) {
                    delay(700)
                    if (YouTubeUi.skipButton(snapshot()) == null) return true
                }
            }
            delay(350)
        }
        Log.i(TAG, "skip ad not found")
        return false
    }

    /** Tap pause, play, or next when that control is actually on screen. */
    suspend fun tapControl(action: String): Boolean {
        val node = YouTubeUi.control(snapshot(), action) ?: return false
        if (YouTubeUi.controlAlready(snapshot(), action)) return true
        val tapped = tap(node)
        Log.i(TAG, "control $action tapped=$tapped")
        return tapped
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<UiNode>): Pair<String, String> {
        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { descs += it }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val words = collect(child, out)
            child.recycle()
            if (words.first.isNotBlank()) texts += words.first
            if (words.second.isNotBlank()) descs += words.second
        }
        val text = texts.joinToString(" ")
        val desc = descs.joinToString(" ")
        val viewId = node.viewIdResourceName.orEmpty()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val useful = node.isClickable || viewId.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty()
        if (useful && bounds.width() > 0 && bounds.height() > 0) {
            out += UiNode(
                text = text,
                desc = desc,
                viewId = viewId,
                clickable = node.isClickable,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
        }
        return text to desc
    }

    private fun performClick(target: UiNode): Boolean {
        val roots = currentRoots()
        var clicked = false
        for (root in roots) {
            if (!clicked) clicked = clickMatching(root, target)
            root.recycle()
        }
        return clicked
    }

    private fun clickMatching(node: AccessibilityNodeInfo, target: UiNode): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val close = kotlin.math.abs(bounds.left - target.left) <= 12 &&
            kotlin.math.abs(bounds.top - target.top) <= 12 &&
            kotlin.math.abs(bounds.right - target.right) <= 12 &&
            kotlin.math.abs(bounds.bottom - target.bottom) <= 12
        if (close && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = clickMatching(child, target)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun scrollForward(): Boolean {
        val roots = currentRoots()
        var scrolled = false
        for (root in roots) {
            if (!scrolled) scrolled = scrollNode(root)
            root.recycle()
        }
        return scrolled
    }

    private fun scrollNode(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName.orEmpty().endsWith("/results") &&
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val scrolled = scrollNode(child)
            child.recycle()
            if (scrolled) return true
        }
        return false
    }

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (window in windows) {
            window.root?.let { roots += it }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots += it }
        return roots
    }

    private fun displaySpan(width: Boolean): Int {
        val metrics = resources.displayMetrics
        val pixels = if (width) metrics.widthPixels else metrics.heightPixels
        val root = rootInActiveWindow
        val bounds = Rect()
        root?.getBoundsInScreen(bounds)
        root?.recycle()
        val fromRoot = if (width) bounds.width() else bounds.height()
        return maxOf(pixels, fromRoot).coerceAtLeast(1)
    }

    private fun tapPoint(node: UiNode): Pair<Int, Int> {
        val x = (node.left + node.right) / 2
        val y = if (node.height < 420) {
            (node.top + node.bottom) / 2
        } else {
            node.top + (node.height * 0.35f).toInt()
        }
        return x to y
    }

    private suspend fun gestureTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture)
    }

    private suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 280)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    companion object {
        private const val TAG = "JarvisAccessibility"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!on) return false
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = ComponentName(context, JarvisAccessibilityService::class.java)
            return enabled.split(':').any { name ->
                name.equals(component.flattenToString(), ignoreCase = true) ||
                    name.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}
