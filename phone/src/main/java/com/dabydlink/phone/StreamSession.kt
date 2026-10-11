package com.dabydlink.phone

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.dabydlink.shared.FrameReader
import com.dabydlink.shared.FrameWriter
import com.dabydlink.shared.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connection to one car: connects, reads the car's HELLO, then streams encoded video
 * while handling touch / navigation messages coming back from the car.
 */
class StreamSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: String,
    private val port: Int,
    private val bitrate: Int,
    private val events: Events,
) : ScreenEncoder.Sink {

    interface Events {
        fun onConnected(carName: String)
        fun onClosed(reason: String)
    }

    private class Packet(val type: Byte, val flags: Int, val pts: Long, val data: ByteArray, val length: Int)

    private val closed = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: FrameWriter? = null
    @Volatile private var encoder: ScreenEncoder? = null
    @Volatile private var carWidth = 1920
    @Volatile private var carHeight = 1080
    @Volatile private var lastConfig: ByteArray? = null
    @Volatile private var dropping = false
    @Volatile private var lastState = -1

    private val queue = ArrayBlockingQueue<Packet>(QUEUE_CAPACITY)
    private val sessionThread = Thread(::run, "dbl-session")
    private var senderThread: Thread? = null

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var lastPhoneSize = Point()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val size = phoneSize()
            if (size != lastPhoneSize) {
                lastPhoneSize = size
                encoder?.requestReconfigure()
            }
        }
    }

    fun start() = sessionThread.start()

    fun stop(reason: String) = close(reason)

    /** Tells the car whether to hide the video (e.g. a video app while the car is moving). */
    fun sendState(videoBlocked: Boolean) {
        val flags = if (videoBlocked) Protocol.STATE_VIDEO_BLOCKED else 0
        if (flags == lastState) return
        lastState = flags
        enqueue(Packet(Protocol.MSG_STATE, 0, 0, byteArrayOf(flags.toByte()), 1))
    }

    // ---- session ----

    private fun run() {
        try {
            val s = connect()
            socket = s
            val w = FrameWriter(DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024)))
            writer = w
            val reader = FrameReader(DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024)))

            reader.next()
            if (reader.type != Protocol.MSG_HELLO) throw IllegalStateException("Not a DaBYDlink car")
            val hello = Protocol.decodeHello(reader.payload, reader.length)
            carWidth = maxOf(hello.width, hello.height).coerceAtLeast(320)
            carHeight = minOf(hello.width, hello.height).coerceAtLeast(240)
            events.onConnected(hello.name)

            senderThread = Thread(::sendLoop, "dbl-sender").also { it.start() }

            lastPhoneSize = phoneSize()
            main.post { displayManager.registerDisplayListener(displayListener, main) }
            val dpi = context.resources.displayMetrics.densityDpi
            encoder = ScreenEncoder(projection, bitrate, dpi, ::videoSize, this).also { it.start() }

            while (!closed.get()) {
                reader.next()
                handle(reader)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session ended", e)
            close(if (socket == null) "Couldn't reach the car at $host" else "Connection to the car was lost")
        }
    }

    private fun handle(r: FrameReader) {
        when (r.type) {
            Protocol.MSG_TOUCH -> TouchService.instance?.onRemoteTouch(Protocol.decodeTouch(r.payload, r.length))
            Protocol.MSG_NAV -> if (r.length > 0) TouchService.instance?.onNav(r.payload[0].toInt())
            Protocol.MSG_REQUEST_KEYFRAME -> {
                lastConfig?.let { enqueueConfig(it) }
                encoder?.requestKeyFrame()
            }
            Protocol.MSG_PING -> enqueue(Packet(Protocol.MSG_PONG, 0, 0, ByteArray(0), 0))
            Protocol.MSG_HELLO -> {
                val h = Protocol.decodeHello(r.payload, r.length)
                carWidth = maxOf(h.width, h.height).coerceAtLeast(320)
                carHeight = minOf(h.width, h.height).coerceAtLeast(240)
                encoder?.requestReconfigure()
            }
        }
    }

    private fun sendLoop() {
        try {
            while (!closed.get()) {
                val p = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val w = writer ?: continue
                if (p.type == Protocol.MSG_VIDEO_FRAME) w.writeFrame(p.flags, p.pts, p.data, p.length)
                else w.write(p.type, p.data, 0, p.length)
            }
        } catch (e: Exception) {
            if (!closed.get()) close("Connection to the car was lost")
        }
    }

    private fun connect(): Socket {
        val addr = InetAddress.getByName(host)
        val s = Socket()
        bindToLocalNetwork(addr, s)
        s.tcpNoDelay = true
        s.sendBufferSize = 512 * 1024
        s.connect(InetSocketAddress(addr, port), 5000)
        s.soTimeout = 10_000 // the car pings every 2 s
        return s
    }

    /**
     * When Wi-Fi has no internet, Android may route app traffic over mobile data.
     * Bind the socket to whichever network actually contains the car's address.
     */
    private fun bindToLocalNetwork(target: InetAddress, s: Socket) {
        if (target !is Inet4Address) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && sameSubnet(a, target, la.prefixLength)) {
                        net.bindSocket(s)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network bind failed", e)
        }
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        if (prefix <= 0) return false
        val ia = Protocol.readIntAt(a.address, 0)
        val ib = Protocol.readIntAt(b.address, 0)
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        return (ia and mask) == (ib and mask)
    }

    // ---- video sizing ----

    @Suppress("DEPRECATION")
    private fun phoneSize(): Point {
        val p = Point()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        if (p.x == 0 || p.y == 0) p.set(1080, 2400)
        return p
    }

    /** Fits the phone screen inside the car screen, keeping its aspect ratio. */
    private fun videoSize(maxLongEdge: Int): Pair<Int, Int> {
        val phone = phoneSize()
        val pw = phone.x.toDouble()
        val ph = phone.y.toDouble()
        val landscape = pw >= ph
        // Car screens are landscape; a portrait phone is fitted by height.
        val boxW = carWidth.toDouble()
        val boxH = carHeight.toDouble()
        var scale = minOf(boxW / pw, boxH / ph, 1.0)
        val longEdge = maxOf(pw, ph) * scale
        if (longEdge > maxLongEdge) scale *= maxLongEdge / longEdge
        fun align(v: Double) = ((v.toInt() / 16) * 16).coerceAtLeast(160)
        val w = align(pw * scale)
        val h = align(ph * scale)
        Log.i(TAG, "Phone ${phone.x}x${phone.y} (landscape=$landscape) -> video ${w}x$h")
        return w to h
    }

    // ---- encoder sink ----

    override fun onConfig(width: Int, height: Int, csd: ByteArray) {
        val payload = Protocol.encodeConfig(width, height, csd)
        lastConfig = payload
        enqueueConfig(payload)
    }

    override fun onFrame(flags: Int, ptsUs: Long, buffer: ByteBuffer, size: Int) {
        val key = flags and Protocol.FRAME_FLAG_KEY != 0
        if (dropping && !key) return
        val data = ByteArray(size)
        buffer.get(data)
        if (!queue.offer(Packet(Protocol.MSG_VIDEO_FRAME, flags, ptsUs, data, size))) {
            // Network can't keep up: drop the backlog and restart from the next key frame,
            // so latency stays low instead of growing.
            queue.clear()
            lastConfig?.let { enqueueConfig(it) }
            dropping = true
            encoder?.requestKeyFrame()
        } else if (key) {
            dropping = false
        }
    }

    override fun onEncoderError(e: Throwable) {
        Log.e(TAG, "Encoder failed", e)
        close("This phone's video encoder stopped working")
    }

    private fun enqueueConfig(payload: ByteArray) {
        if (!queue.offer(Packet(Protocol.MSG_VIDEO_CONFIG, 0, 0, payload, payload.size))) {
            queue.clear()
            queue.offer(Packet(Protocol.MSG_VIDEO_CONFIG, 0, 0, payload, payload.size))
            dropping = true
            encoder?.requestKeyFrame()
        }
    }

    private fun enqueue(p: Packet) {
        if (!queue.offer(p)) {
            queue.clear()
            lastConfig?.let { enqueueConfig(it) }
            queue.offer(p)
            dropping = true
            encoder?.requestKeyFrame()
        }
    }

    // ---- teardown ----

    private fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        main.post { displayManager.unregisterDisplayListener(displayListener) }
        try { socket?.close() } catch (_: Exception) {}
        encoder?.release()
        encoder = null
        senderThread?.interrupt()
        queue.clear()
        events.onClosed(reason)
    }

    companion object {
        private const val TAG = "DaBYDlink"
        private const val QUEUE_CAPACITY = 12
    }
}
