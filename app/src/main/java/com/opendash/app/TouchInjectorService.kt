package com.opendash.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Turns taps/swipes on the car screen into real touches on the phone,
 * and reports which app is in front (for the driving lock).
 */
class TouchInjectorService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TouchInjectorService? = null
            private set

        @Volatile
        var foregroundPackage: String? = null
            private set

        val packageListeners = CopyOnWriteArraySet<(String) -> Unit>()

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, TouchInjectorService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }

        private val IGNORED = setOf("com.android.systemui", "android")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED || pkg == foregroundPackage) return
        foregroundPackage = pkg
        packageListeners.forEach { it(pkg) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float) = stroke(Path().apply { moveTo(x, y) }, 60)

    fun longPress(x: Float, y: Float) = stroke(Path().apply { moveTo(x, y) }, 650)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun dispatch(gesture: GestureDescription): Boolean = dispatchGesture(gesture, null, null)

    private fun stroke(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        return dispatch(g)
    }
}
