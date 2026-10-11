package com.dabydlink.phone

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Captures the phone screen through MediaProjection and encodes it to H.264.
 *
 * The virtual display is created once (Android 14 forbids creating a second one from the same
 * projection) and is resized + re-pointed at a fresh encoder surface whenever the phone rotates
 * or the car reports a new screen size.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val bitrate: Int,
    private val dpi: Int,
    /** Returns the target (width, height) for a given maximum long edge. */
    private val sizeFor: (maxLongEdge: Int) -> Pair<Int, Int>,
    private val sink: Sink,
) {
    interface Sink {
        fun onConfig(width: Int, height: Int, csd: ByteArray)
        fun onFrame(flags: Int, ptsUs: Long, buffer: ByteBuffer, size: Int)
        fun onEncoderError(e: Throwable)
    }

    @Volatile private var running = true
    @Volatile private var reconfigure = false
    @Volatile private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private val thread = Thread(::loop, "dbl-encoder")

    fun start() = thread.start()

    fun requestReconfigure() {
        reconfigure = true
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    fun release() {
        running = false
        if (Thread.currentThread() === thread) return
        try {
            thread.join(1500)
        } catch (_: InterruptedException) {
        }
    }

    private fun loop() {
        try {
            while (running) {
                reconfigure = false
                val (c, w, h) = createCodec()
                val surface = c.createInputSurface()
                c.start()
                codec = c
                val vd = display
                if (vd == null) {
                    display = projection.createVirtualDisplay(
                        "DaBYDlink", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null,
                    )
                } else {
                    vd.surface = null
                    vd.resize(w, h, dpi)
                    vd.surface = surface
                }
                Log.i(TAG, "Encoding ${w}x$h @ ${bitrate / 1000} kbps")
                try {
                    drain(c, w, h)
                } finally {
                    display?.surface = null
                    codec = null
                    try { c.stop() } catch (_: Exception) {}
                    c.release()
                    surface.release()
                }
            }
        } catch (e: Throwable) {
            if (running) sink.onEncoderError(e)
        } finally {
            try { display?.release() } catch (_: Exception) {}
            display = null
        }
    }

    private fun drain(c: MediaCodec, w: Int, h: Int) {
        val info = MediaCodec.BufferInfo()
        while (running && !reconfigure) {
            val idx = c.dequeueOutputBuffer(info, 100_000)
            if (idx < 0) continue
            val buf = c.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val csd = ByteArray(info.size)
                    buf.get(csd)
                    sink.onConfig(w, h, csd)
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    sink.onFrame(if (key) 1 else 0, info.presentationTimeUs, buf, info.size)
                }
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    /** Tries full size first, then smaller sizes for encoders that refuse large frames. */
    private fun createCodec(): Triple<MediaCodec, Int, Int> {
        var last: Exception? = null
        for (maxEdge in intArrayOf(1920, 1280, 960)) {
            val (w, h) = sizeFor(maxEdge)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                c.configure(format(w, h), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Triple(c, w, h)
            } catch (e: Exception) {
                Log.w(TAG, "Encoder refused ${w}x$h", e)
                last = e
                c.release()
            }
        }
        throw last ?: IllegalStateException("No usable encoder")
    }

    private fun format(w: Int, h: Int) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 60f)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        // Keep sending frames on a static screen so the car never shows a stale image.
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
    }

    companion object {
        private const val TAG = "DaBYDlink"
    }
}
