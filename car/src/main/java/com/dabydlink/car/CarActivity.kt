package com.dabydlink.car

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.dabydlink.shared.Protocol

class CarActivity : Activity(), LinkServer.Listener {

    private lateinit var server: LinkServer
    private lateinit var player: VideoPlayer
    private val main = Handler(Looper.getMainLooper())

    private lateinit var idleView: View
    private lateinit var addressText: TextView
    private lateinit var dock: View
    private lateinit var videoContainer: FrameLayout
    private lateinit var videoView: SurfaceView
    private lateinit var waitingText: TextView
    private lateinit var blockedOverlay: View

    private var videoWidth = 0
    private var videoHeight = 0
    private var connected = false

    private val refreshAddresses = object : Runnable {
        override fun run() {
            renderAddresses()
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_car)

        idleView = findViewById(R.id.idleView)
        addressText = findViewById(R.id.addressText)
        dock = findViewById(R.id.dock)
        videoContainer = findViewById(R.id.videoContainer)
        videoView = findViewById(R.id.videoView)
        waitingText = findViewById(R.id.waitingText)
        blockedOverlay = findViewById(R.id.blockedOverlay)

        player = VideoPlayer(
            onNeedKeyFrame = { server.requestKeyFrame() },
            onFirstFrame = { main.post { waitingText.visibility = View.GONE } },
        )
        server = LinkServer("DaBYDlink Car · ${Build.MODEL}", ::hello, this)

        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player.setSurface(null)
            }
        })
        setupTouch()
        videoContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        findViewById<View>(R.id.btnBack).setOnClickListener { server.sendNav(Protocol.NAV_BACK) }
        findViewById<View>(R.id.btnHome).setOnClickListener { server.sendNav(Protocol.NAV_HOME) }
        findViewById<View>(R.id.btnRecents).setOnClickListener { server.sendNav(Protocol.NAV_RECENTS) }
        findViewById<View>(R.id.btnDisconnect).setOnClickListener { server.disconnect() }

        renderConnection()
        server.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        main.post(refreshAddresses)
    }

    override fun onPause() {
        main.removeCallbacks(refreshAddresses)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        server.sendHello() // the car screen may rotate on some models
    }

    override fun onDestroy() {
        server.stop()
        player.reset()
        super.onDestroy()
    }

    // ---- server callbacks (background threads) ----

    override fun onPhoneConnected(address: String) {
        player.reset()
        main.post {
            connected = true
            renderConnection()
        }
    }

    override fun onPhoneDisconnected() {
        player.reset()
        main.post {
            connected = false
            blockedOverlay.visibility = View.GONE
            renderConnection()
        }
    }

    override fun onVideoConfig(width: Int, height: Int, csd: ByteArray, offset: Int, length: Int) {
        player.onConfig(width, height, csd, offset, length)
        main.post {
            if (width != videoWidth || height != videoHeight) {
                videoWidth = width
                videoHeight = height
                layoutVideo()
            }
        }
    }

    override fun onVideoFrame(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        player.onFrame(flags, ptsUs, data, offset, length)
    }

    override fun onState(flags: Int) {
        val blocked = flags and Protocol.STATE_VIDEO_BLOCKED != 0
        main.post { blockedOverlay.visibility = if (blocked) View.VISIBLE else View.GONE }
    }

    // ---- UI ----

    private fun renderConnection() {
        idleView.visibility = if (connected) View.GONE else View.VISIBLE
        dock.visibility = if (connected) View.VISIBLE else View.GONE
        waitingText.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) renderAddresses()
    }

    private fun renderAddresses() {
        val ips = Protocol.localIpv4().map { it.second.hostAddress }.filterNotNull()
        addressText.text = if (ips.isEmpty()) {
            "Not on Wi-Fi yet. Connect the car to your phone's hotspot (or turn on the car hotspot)."
        } else {
            "Car address: " + ips.joinToString("   ·   ")
        }
    }

    /** Fits the phone picture inside the available area without stretching it. */
    private fun layoutVideo() {
        val cw = videoContainer.width
        val ch = videoContainer.height
        if (cw == 0 || ch == 0 || videoWidth == 0 || videoHeight == 0) return
        val scale = minOf(cw.toFloat() / videoWidth, ch.toFloat() / videoHeight)
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        val lp = videoView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            videoView.layoutParams = lp
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        videoView.setOnTouchListener { v, e ->
            if (!connected || v.width == 0 || v.height == 0) return@setOnTouchListener false
            fun send(action: Int, index: Int) {
                val id = e.getPointerId(index)
                if (id > 1) return
                server.sendTouch(Protocol.Touch(action, id, e.getX(index) / v.width, e.getY(index) / v.height))
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(Protocol.TOUCH_DOWN, e.actionIndex)
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) send(Protocol.TOUCH_MOVE, i)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(Protocol.TOUCH_UP, e.actionIndex)
                MotionEvent.ACTION_CANCEL -> send(Protocol.TOUCH_CANCEL, 0)
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun hello(): Protocol.Hello {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return Protocol.Hello(Protocol.VERSION, dm.widthPixels, dm.heightPixels, dm.densityDpi, "DaBYDlink Car")
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }
}
