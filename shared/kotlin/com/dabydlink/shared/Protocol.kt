package com.dabydlink.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Wire protocol shared by the phone app (sender) and the car app (receiver).
 *
 * Every message on the TCP stream is framed as:
 *   [type: 1 byte][length: 4 bytes, big endian][payload: length bytes]
 *
 * Discovery: the car broadcasts a UDP beacon every second on [DISCOVERY_PORT]:
 *   "DABYDLINK|<version>|<car name>|<tcp port>"
 */
object Protocol {
    const val MAGIC = "DABYDLINK"
    const val VERSION = 1
    const val DISCOVERY_PORT = 47100
    const val STREAM_PORT = 47101
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    // car -> phone
    const val MSG_HELLO: Byte = 0x01
    const val MSG_TOUCH: Byte = 0x10
    const val MSG_NAV: Byte = 0x11
    const val MSG_REQUEST_KEYFRAME: Byte = 0x12
    const val MSG_PING: Byte = 0x13

    // phone -> car
    const val MSG_VIDEO_CONFIG: Byte = 0x20
    const val MSG_VIDEO_FRAME: Byte = 0x21
    const val MSG_STATE: Byte = 0x22
    const val MSG_PONG: Byte = 0x23

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    const val NAV_BACK = 1
    const val NAV_HOME = 2
    const val NAV_RECENTS = 3

    const val FRAME_FLAG_KEY = 1

    const val STATE_VIDEO_BLOCKED = 1

    // ---- beacon ----

    fun beacon(name: String, port: Int): ByteArray =
        "$MAGIC|$VERSION|${name.replace('|', ' ')}|$port".toByteArray(Charsets.UTF_8)

    data class Beacon(val name: String, val port: Int)

    fun parseBeacon(data: ByteArray, length: Int): Beacon? {
        val parts = String(data, 0, length, Charsets.UTF_8).split('|')
        if (parts.size < 4 || parts[0] != MAGIC) return null
        val port = parts[3].toIntOrNull() ?: return null
        return Beacon(parts[2], port)
    }

    // ---- payload helpers ----

    data class Hello(val version: Int, val width: Int, val height: Int, val dpi: Int, val name: String)

    fun encodeHello(h: Hello): ByteArray = build {
        writeInt(h.version); writeInt(h.width); writeInt(h.height); writeInt(h.dpi); writeUTF(h.name)
    }

    fun decodeHello(p: ByteArray, len: Int): Hello = read(p, len) {
        Hello(readInt(), readInt(), readInt(), readInt(), readUTF())
    }

    data class Touch(val action: Int, val pointerId: Int, val x: Float, val y: Float)

    fun encodeTouch(t: Touch): ByteArray = build {
        writeByte(t.action); writeByte(t.pointerId); writeFloat(t.x); writeFloat(t.y)
    }

    fun decodeTouch(p: ByteArray, len: Int): Touch = read(p, len) {
        Touch(readByte().toInt(), readByte().toInt(), readFloat(), readFloat())
    }

    /** VIDEO_CONFIG payload: width, height, then the codec config bytes (SPS/PPS). */
    fun encodeConfig(width: Int, height: Int, csd: ByteArray): ByteArray = build {
        writeInt(width); writeInt(height); write(csd)
    }

    const val CONFIG_HEADER = 8
    const val FRAME_HEADER = 9 // flags (1) + pts us (8)

    private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(32)
        DataOutputStream(bos).use(block)
        return bos.toByteArray()
    }

    private inline fun <T> read(p: ByteArray, len: Int, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(p, 0, len)).use(block)

    // ---- networking helpers ----

    fun readIntAt(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xff) shl 24) or ((p[off + 1].toInt() and 0xff) shl 16) or
            ((p[off + 2].toInt() and 0xff) shl 8) or (p[off + 3].toInt() and 0xff)

    fun readLongAt(p: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (p[off + i].toLong() and 0xff)
        return v
    }

    /** IPv4 addresses of every active non-loopback interface (Wi-Fi, hotspot, ...). */
    fun localIpv4(): List<Pair<String, InetAddress>> {
        val result = mutableListOf<Pair<String, InetAddress>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address) result += nif.name to addr
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Broadcast address of every active IPv4 interface. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast
                    if (ia.address is Inet4Address && b != null) result += b
                }
            }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) result += InetAddress.getByName("255.255.255.255")
        return result
    }
}

/** Thread-safe framed writer. */
class FrameWriter(private val out: DataOutputStream) {
    @Synchronized
    fun write(type: Byte, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        out.writeByte(type.toInt())
        out.writeInt(length)
        out.write(payload, offset, length)
        out.flush()
    }

    /** Writes a video frame without building an intermediate payload array. */
    @Synchronized
    fun writeFrame(flags: Int, ptsUs: Long, data: ByteArray, length: Int) {
        out.writeByte(Protocol.MSG_VIDEO_FRAME.toInt())
        out.writeInt(Protocol.FRAME_HEADER + length)
        out.writeByte(flags)
        out.writeLong(ptsUs)
        out.write(data, 0, length)
        out.flush()
    }
}

/** Framed reader that reuses its buffer. */
class FrameReader(private val input: DataInputStream) {
    var type: Byte = 0
        private set
    var payload = ByteArray(256 * 1024)
        private set
    var length = 0
        private set

    fun next() {
        type = input.readByte()
        val len = input.readInt()
        if (len < 0 || len > Protocol.MAX_PAYLOAD) throw IOException("Bad frame length $len")
        if (payload.size < len) payload = ByteArray(maxOf(len, payload.size * 2))
        input.readFully(payload, 0, len)
        length = len
    }
}
