package com.dabydlink.car

import android.util.Log
import com.dabydlink.shared.FrameReader
import com.dabydlink.shared.FrameWriter
import com.dabydlink.shared.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs on the car screen: announces itself on the local network, accepts one phone at a time,
 * receives video and sends touches back.
 */
class LinkServer(
    private val carName: String,
    private val helloProvider: () -> Protocol.Hello,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhoneConnected(address: String)
        fun onPhoneDisconnected()
        fun onVideoConfig(width: Int, height: Int, csd: ByteArray, offset: Int, length: Int)
        fun onVideoFrame(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int)
        fun onState(flags: Int)
    }

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var writer: FrameWriter? = null
    private var serverSocket: ServerSocket? = null
    private val outbound = Executors.newSingleThreadExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        if (running) return
        running = true
        Thread(::acceptLoop, "dbl-accept").start()
        timers.scheduleWithFixedDelay(::beacon, 0, 1, TimeUnit.SECONDS)
        timers.scheduleWithFixedDelay({ send(Protocol.MSG_PING, ByteArray(0)) }, 2, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        disconnect()
        timers.shutdownNow()
        outbound.shutdownNow()
    }

    val isConnected: Boolean get() = client != null

    fun sendTouch(t: Protocol.Touch) = send(Protocol.MSG_TOUCH, Protocol.encodeTouch(t))
    fun sendNav(code: Int) = send(Protocol.MSG_NAV, byteArrayOf(code.toByte()))
    fun requestKeyFrame() = send(Protocol.MSG_REQUEST_KEYFRAME, ByteArray(0))
    fun sendHello() = send(Protocol.MSG_HELLO, Protocol.encodeHello(helloProvider()))

    fun disconnect() {
        val c = client ?: return
        try { c.close() } catch (_: Exception) {}
    }

    private fun send(type: Byte, payload: ByteArray) {
        val w = writer ?: return
        val c = client
        try {
            outbound.execute {
                try {
                    w.write(type, payload)
                } catch (e: Exception) {
                    try { c?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // executor shut down
        }
    }

    // ---- discovery beacon ----

    private var beaconSocket: DatagramSocket? = null

    private fun beacon() {
        if (!running) return
        try {
            val s = beaconSocket ?: DatagramSocket().apply { broadcast = true }.also { beaconSocket = it }
            val data = Protocol.beacon(carName, Protocol.STREAM_PORT)
            for (addr in Protocol.broadcastAddresses()) {
                try {
                    s.send(DatagramPacket(data, data.size, addr, Protocol.DISCOVERY_PORT))
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Beacon failed", e)
            beaconSocket?.close()
            beaconSocket = null
        }
    }

    // ---- stream ----

    private fun acceptLoop() {
        while (running) {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.STREAM_PORT))
                }
                serverSocket = ss
                while (running) {
                    val s = ss.accept()
                    disconnect() // newest phone wins
                    Thread({ serve(s) }, "dbl-client").start()
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Server socket error, retrying", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.receiveBufferSize = 1024 * 1024
            s.soTimeout = 8000
            val w = FrameWriter(DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16 * 1024)))
            client = s
            writer = w
            w.write(Protocol.MSG_HELLO, Protocol.encodeHello(helloProvider()))
            listener.onPhoneConnected(s.inetAddress.hostAddress ?: "phone")
            val r = FrameReader(DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024)))
            while (running) {
                r.next()
                val p = r.payload
                when (r.type) {
                    Protocol.MSG_VIDEO_CONFIG -> if (r.length > Protocol.CONFIG_HEADER) {
                        listener.onVideoConfig(
                            Protocol.readIntAt(p, 0), Protocol.readIntAt(p, 4),
                            p, Protocol.CONFIG_HEADER, r.length - Protocol.CONFIG_HEADER,
                        )
                    }
                    Protocol.MSG_VIDEO_FRAME -> if (r.length > Protocol.FRAME_HEADER) {
                        listener.onVideoFrame(
                            p[0].toInt(), Protocol.readLongAt(p, 1),
                            p, Protocol.FRAME_HEADER, r.length - Protocol.FRAME_HEADER,
                        )
                    }
                    Protocol.MSG_STATE -> if (r.length > 0) listener.onState(p[0].toInt())
                    Protocol.MSG_PONG -> {}
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Phone disconnected: ${e.message}")
        } finally {
            try { s.close() } catch (_: Exception) {}
            if (client === s) {
                client = null
                writer = null
                listener.onPhoneDisconnected()
            }
        }
    }

    companion object {
        private const val TAG = "DaBYDlinkCar"
    }
}
