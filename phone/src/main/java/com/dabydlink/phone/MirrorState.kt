package com.dabydlink.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide mirroring state shared between the service and the UI. */
object MirrorState {
    enum class Phase { IDLE, CONNECTING, STREAMING }

    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var carName: String = ""
        private set
    @Volatile var message: String = ""
        private set
    @Volatile var videoBlocked: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(phase: Phase, carName: String = this.carName, message: String = "") {
        this.phase = phase
        this.carName = carName
        this.message = message
        if (phase != Phase.STREAMING) videoBlocked = false
        notifyListeners()
    }

    fun setVideoBlocked(blocked: Boolean) {
        if (videoBlocked == blocked) return
        videoBlocked = blocked
        notifyListeners()
    }

    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}

/** Simple persisted settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dabydlink", Context.MODE_PRIVATE)

    /** 0 = smooth, 1 = balanced, 2 = sharp */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) = sp.edit().putInt("quality", v).apply()

    var drivingGuard: Boolean
        get() = sp.getBoolean("driving_guard", true)
        set(v) = sp.edit().putBoolean("driving_guard", v).apply()

    var lastIp: String
        get() = sp.getString("last_ip", "") ?: ""
        set(v) = sp.edit().putString("last_ip", v).apply()

    val bitrate: Int
        get() = when (quality) {
            0 -> 4_000_000
            2 -> 12_000_000
            else -> 8_000_000
        }
}
