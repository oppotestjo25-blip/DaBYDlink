package com.dabydlink.car

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/** Low-latency H.264 decoder that renders straight to the car screen's Surface. */
class VideoPlayer(
    private val onNeedKeyFrame: () -> Unit,
    private val onFirstFrame: () -> Unit,
) {
    private val lock = Object()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var csd: ByteArray? = null
    private var awaitingKey = true
    private var lastKeyRequest = 0L

    @Volatile private var draining = false
    private var drainThread: Thread? = null

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
            releaseCodec()
            if (s != null && csd != null) startCodec()
        }
        if (s != null) requestKey(force = true)
    }

    /** Forget the current stream (new phone, or phone left). */
    fun reset() = synchronized(lock) {
        releaseCodec()
        csd = null
        width = 0
        height = 0
    }

    fun onConfig(w: Int, h: Int, data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        val newCsd = data.copyOfRange(offset, offset + length)
        if (codec != null && w == width && h == height && newCsd.contentEquals(csd)) return@synchronized
        width = w
        height = h
        csd = newCsd
        releaseCodec()
        if (surface != null) startCodec()
    }

    fun onFrame(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        var needKey = false
        synchronized(lock) {
            val c = codec ?: return
            val key = flags and 1 != 0
            if (awaitingKey && !key) {
                needKey = true
            } else {
                try {
                    val idx = c.dequeueInputBuffer(20_000)
                    if (idx < 0) {
                        // Decoder is behind; skipping a frame breaks the picture until the next key frame.
                        awaitingKey = true
                        needKey = true
                    } else {
                        val buf = c.getInputBuffer(idx)!!
                        buf.clear()
                        buf.put(data, offset, length)
                        c.queueInputBuffer(idx, 0, length, ptsUs, 0)
                        if (key) awaitingKey = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Decoder input failed", e)
                    releaseCodec()
                    if (surface != null && csd != null) startCodec()
                    needKey = true
                }
            }
        }
        if (needKey) requestKey(force = false)
    }

    private fun requestKey(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastKeyRequest < 500) return
        lastKeyRequest = now
        onNeedKeyFrame()
    }

    // ---- codec lifecycle (call with lock held) ----

    private fun startCodec() {
        val s = surface ?: return
        val config = csd ?: return
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                if (Build.VERSION.SDK_INT >= 30 &&
                    c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                ) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Vendor low-latency switches; ignored by decoders that don't know them.
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            c.configure(format, s, null, 0)
            c.start()
            val idx = c.dequeueInputBuffer(200_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)!!
                buf.clear()
                buf.put(config)
                c.queueInputBuffer(idx, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            codec = c
            awaitingKey = true
            startDrain(c)
            Log.i(TAG, "Decoder started ${width}x$height (${c.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder start failed", e)
            codec = null
        }
    }

    private fun startDrain(c: MediaCodec) {
        draining = true
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var first = true
            while (draining) {
                try {
                    val idx = c.dequeueOutputBuffer(info, 10_000)
                    if (idx >= 0) {
                        c.releaseOutputBuffer(idx, info.size > 0)
                        if (first && info.size > 0) {
                            first = false
                            onFirstFrame()
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }, "dbl-decoder").also { it.start() }
    }

    private fun releaseCodec() {
        draining = false
        try { drainThread?.join(500) } catch (_: InterruptedException) {}
        drainThread = null
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "DaBYDlinkCar"
    }
}
