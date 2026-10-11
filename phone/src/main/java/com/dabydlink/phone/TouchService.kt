package com.dabydlink.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.dabydlink.shared.Protocol
import kotlin.math.hypot

/**
 * Replays touches made on the car screen as gestures on the phone, and reports which app is in
 * the foreground (used by the driving guard).
 *
 * Gestures are dispatched when the last finger lifts, so taps, long-presses, swipes and
 * two-finger pinches all work; a drag appears on the phone right after the finger is released.
 */
class TouchService : AccessibilityService() {

    private class Pt(val x: Float, val y: Float, val t: Long)
    private class Stroke(val points: MutableList<Pt>)

    private val main = Handler(Looper.getMainLooper())
    private val active = HashMap<Int, Stroke>()
    private val finished = ArrayList<Stroke>()
    private var gestureStart = 0L

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui") return
        foregroundPackage = pkg
        MirrorService.instance?.onForegroundAppChanged()
    }

    override fun onInterrupt() {}

    fun onRemoteTouch(t: Protocol.Touch) {
        main.post { process(t) }
    }

    fun onNav(code: Int) {
        main.post {
            when (code) {
                Protocol.NAV_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                Protocol.NAV_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                Protocol.NAV_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }

    private fun process(t: Protocol.Touch) {
        if (t.pointerId !in 0..1) return
        val size = screenSize()
        val x = t.x.coerceIn(0f, 1f) * (size.x - 1)
        val y = t.y.coerceIn(0f, 1f) * (size.y - 1)
        val now = SystemClock.uptimeMillis()
        when (t.action) {
            Protocol.TOUCH_DOWN -> {
                if (active.isEmpty() && finished.isEmpty()) gestureStart = now
                active[t.pointerId] = Stroke(mutableListOf(Pt(x, y, now)))
            }
            Protocol.TOUCH_MOVE -> active[t.pointerId]?.let { s ->
                val last = s.points.last()
                if (hypot(x - last.x, y - last.y) >= 3f) s.points += Pt(x, y, now)
                if (now - gestureStart > MAX_GESTURE_MS) flushAll()
            }
            Protocol.TOUCH_UP -> {
                active.remove(t.pointerId)?.let { s ->
                    s.points += Pt(x, y, now)
                    finished += s
                }
                if (active.isEmpty()) dispatch()
            }
            Protocol.TOUCH_CANCEL -> {
                active.clear()
                finished.clear()
            }
        }
    }

    private fun flushAll() {
        finished.addAll(active.values)
        active.clear()
        dispatch()
    }

    private fun dispatch() {
        val strokes = finished.take(GestureDescription.getMaxStrokeCount())
        finished.clear()
        if (strokes.isEmpty()) return
        val maxDur = GestureDescription.getMaxGestureDuration()
        val builder = GestureDescription.Builder()
        for (s in strokes) {
            val pts = s.points
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            val start = (pts.first().t - gestureStart).coerceIn(0L, maxDur - MIN_STROKE_MS)
            val duration = (pts.last().t - pts.first().t)
                .coerceAtLeast(MIN_STROKE_MS)
                .coerceAtMost(maxDur - start)
            builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
        }
        try {
            dispatchGesture(builder.build(), null, null)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val p = Point()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        if (p.x == 0 || p.y == 0) p.set(1080, 2400)
        return p
    }

    companion object {
        private const val MIN_STROKE_MS = 40L
        private const val MAX_GESTURE_MS = 20_000L

        @Volatile var instance: TouchService? = null
            private set

        @Volatile var foregroundPackage: String? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, TouchService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
