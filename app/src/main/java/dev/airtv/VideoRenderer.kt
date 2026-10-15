package dev.airtv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Hardware-decodes the mirrored H.264/H.265 stream onto a Surface.
 *
 * Frames arrive as Annex-B access units. Key frames carry the parameter sets
 * (SPS/PPS, plus VPS for H.265) in front of the picture; whenever those change
 * (e.g. the phone rotates) the decoder is rebuilt with the new configuration.
 *
 * The codec runs in asynchronous mode on its own thread: input is queued the
 * moment both a frame and a codec buffer are available. Decoded pictures are
 * presented on the sender's own timeline (its timestamps mapped onto our clock
 * plus a small fixed delay), which absorbs Wi-Fi jitter so playback is smooth.
 */
class VideoRenderer(
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onStats: (fps: Float, latencyMs: Long) -> Unit = { _, _ -> },
) {
    private class Frame(val data: ByteArray, val isH265: Boolean, val remoteUs: Long, val arrivalUs: Long)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // All state below is only touched on the codec thread.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecConfig: ByteArray? = null
    private var renderedFrame = false
    private val pendingFrames = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()
    private val stats = Stats()
    private val clock = SenderClock()
    private val arrivals = HashMap<Long, Long>() // codec pts -> local arrival, for latency stats
    // Codec timestamps are kept small (relative to the session's first frame);
    // some hardware decoders misbehave with the sender's raw 64-bit clock values.
    private var baseRemoteUs = -1L

    fun start() {
        if (thread != null) return
        val t = HandlerThread("AirTV-video", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = t
        handler = Handler(t.looper)
        handler?.postDelayed(statsTick, STATS_INTERVAL_MS)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            stats.tick(codec != null, pendingFrames.size, freeInputs.size)?.let { (fps, latency) -> onStats(fps, latency) }
            handler?.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    fun stop() {
        val h = handler ?: return
        h.removeCallbacks(statsTick)
        h.post { releaseCodec() }
        thread?.quitSafely()
        thread?.join(1000)
        thread = null
        handler = null
    }

    fun setSurface(surface: Surface?) {
        handler?.post {
            this.surface = surface
            releaseCodec()
        } ?: run { this.surface = surface }
    }

    fun submit(data: ByteArray, isH265: Boolean, ptsUs: Long) {
        val frame = Frame(data, isH265, ptsUs, System.nanoTime() / 1000)
        handler?.post { onFrame(frame) }
    }

    /** Client stopped mirroring: drop decoder state so the next session starts fresh. */
    fun reset() {
        handler?.post { releaseCodec() }
    }

    private fun onFrame(frame: Frame) {
        stats.received++
        clock.observe(frame.remoteUs, frame.arrivalUs)
        val config = NalUtil.parameterSets(frame.data, frame.isH265)
        if (config != null && !config.contentEquals(codecConfig)) {
            releaseCodec()
            createCodec(config, frame.isH265)
        }
        if (codec == null) {
            stats.discarded++ // still waiting for a key frame with parameter sets
            return
        }
        if (pendingFrames.size >= MAX_PENDING) {
            // Decoder can't keep up: drop the backlog, the next key frame resyncs.
            Log.w(TAG, "decoder backlog of ${pendingFrames.size} frames, dropping")
            stats.dropped += pendingFrames.size
            pendingFrames.clear()
        }
        pendingFrames.add(frame)
        feed()
    }

    private fun feed() {
        val codec = codec ?: return
        while (pendingFrames.isNotEmpty() && freeInputs.isNotEmpty()) {
            val frame = pendingFrames.poll()!!
            val index = freeInputs.poll()!!
            try {
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                if (frame.data.size > buffer.capacity()) {
                    Log.w(TAG, "frame of ${frame.data.size} bytes exceeds input buffer, skipped")
                    freeInputs.addFirst(index)
                    continue
                }
                buffer.put(frame.data)
                if (baseRemoteUs < 0) baseRemoteUs = frame.remoteUs
                val ptsUs = maxOf(0L, frame.remoteUs - baseRemoteUs)
                if (arrivals.size > 256) arrivals.clear()
                arrivals[ptsUs] = frame.arrivalUs
                codec.queueInputBuffer(index, 0, frame.data.size, ptsUs, 0)
                stats.queued++
            } catch (e: IllegalStateException) {
                Log.e(TAG, "decoder error, recreating", e)
                releaseCodec()
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            freeInputs.add(index)
            feed()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (mc !== codec) return
            val nowUs = System.nanoTime() / 1000
            val targetUs = clock.presentationTime(info.presentationTimeUs + baseRemoteUs)
            try {
                if (targetUs > nowUs && targetUs - nowUs < MAX_SCHEDULE_AHEAD_US) {
                    mc.releaseOutputBuffer(index, targetUs * 1000)
                } else {
                    mc.releaseOutputBuffer(index, true)
                }
            } catch (e: IllegalStateException) {
                return
            }
            val arrival = arrivals.remove(info.presentationTimeUs) ?: nowUs
            stats.rendered(maxOf(targetUs, nowUs) - arrival)
            if (!renderedFrame) {
                renderedFrame = true
                onFirstFrame()
            }
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            if (mc === codec) reportSize(format)
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "decoder error, recreating", e)
            if (mc === codec) releaseCodec()
        }
    }

    private fun reportSize(format: MediaFormat) {
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        Log.i(TAG, "video size ${width}x$height")
        onVideoSize(width, height)
    }

    private fun createCodec(config: ByteArray, isH265: Boolean) {
        val surface = surface ?: return
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 1920)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (isH265) {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
            } else {
                val (sps, pps) = NalUtil.splitAvcConfig(config)
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
        }
        try {
            val created = MediaCodec.createDecoderByType(mime)
            created.setCallback(callback, handler)
            created.configure(format, surface, null, 0)
            codec = created
            codecConfig = config
            created.start()
            Log.i(TAG, "started $mime decoder ${created.name}")
        } catch (e: Exception) {
            Log.e(TAG, "could not start $mime decoder", e)
            codec = null
            codecConfig = null
        }
    }

    private fun releaseCodec() {
        codec?.let {
            codec = null
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codecConfig = null
        renderedFrame = false
        pendingFrames.clear()
        freeInputs.clear()
        arrivals.clear()
        baseRemoteUs = -1L
        clock.reset()
    }

    /**
     * Maps sender timestamps onto System.nanoTime. The offset is the smallest
     * (arrival - sent) seen recently, i.e. the fastest a frame has made it here;
     * every frame is then shown at sent + offset + [JITTER_DELAY_US], so frames
     * that arrive late by less than that delay still appear on schedule.
     * The offset is re-measured every few seconds to follow clock drift.
     */
    private class SenderClock {
        private var offsetUs: Long? = null
        private var windowMinUs = Long.MAX_VALUE
        private var windowStartUs = 0L

        fun observe(remoteUs: Long, arrivalUs: Long) {
            val d = arrivalUs - remoteUs
            val current = offsetUs
            if (current == null || d < current) {
                offsetUs = d
            }
            windowMinUs = minOf(windowMinUs, d)
            if (windowStartUs == 0L) windowStartUs = arrivalUs
            if (arrivalUs - windowStartUs > RESYNC_WINDOW_US) {
                offsetUs = windowMinUs
                windowMinUs = Long.MAX_VALUE
                windowStartUs = arrivalUs
            }
        }

        fun presentationTime(remoteUs: Long): Long = remoteUs + (offsetUs ?: 0L) + JITTER_DELAY_US

        fun reset() {
            offsetUs = null
            windowMinUs = Long.MAX_VALUE
            windowStartUs = 0L
        }
    }

    /** Pipeline counters, logged on a timer so stalls show up too. */
    private class Stats {
        var received = 0
        var discarded = 0
        var queued = 0
        var dropped = 0
        private var rendered = 0
        private var latencySumUs = 0L
        private var latencyMaxUs = 0L
        private var windowStart = SystemClock.elapsedRealtime()
        private var ticks = 0

        fun rendered(latencyUs: Long) {
            rendered++
            latencySumUs += latencyUs
            latencyMaxUs = maxOf(latencyMaxUs, latencyUs)
        }

        /** Called every second; returns (output fps, average latency ms) for the on-screen counter. */
        fun tick(hasCodec: Boolean, pending: Int, freeInputs: Int): Pair<Float, Long>? {
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - windowStart).coerceAtLeast(1)
            val result = if (received > 0 || rendered > 0) {
                (rendered * 1000f / elapsed) to (if (rendered > 0) latencySumUs / rendered / 1000 else 0L)
            } else {
                null
            }
            if (result != null && ++ticks % 3 == 0) {
                Log.i(
                    TAG, "stats: in %.1f fps, out %.1f fps, arrival-to-screen avg %d ms max %d ms | queued %d, discarded %d, dropped %d, pending %d, free inputs %d, codec %s".format(
                        received * 1000f / elapsed, rendered * 1000f / elapsed,
                        if (rendered > 0) latencySumUs / rendered / 1000 else 0, latencyMaxUs / 1000,
                        queued, discarded, dropped, pending, freeInputs, if (hasCodec) "yes" else "no",
                    )
                )
            }
            received = 0
            discarded = 0
            queued = 0
            dropped = 0
            rendered = 0
            latencySumUs = 0
            latencyMaxUs = 0
            windowStart = now
            return result
        }
    }

    private companion object {
        const val TAG = "AirTV-video"
        const val MAX_PENDING = 30
        const val JITTER_DELAY_US = 35_000L
        const val MAX_SCHEDULE_AHEAD_US = 500_000L
        const val RESYNC_WINDOW_US = 5_000_000L
        const val STATS_INTERVAL_MS = 1000L
    }
}
