package dev.airtv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays AirPlay video casts: the sender hands over a stream URL and the TV fetches and
 * decodes it itself, so playback runs at the stream's own frame rate (not the ~30 fps of
 * iOS screen capture) and keeps going with the sender's screen locked.
 *
 * Commands may arrive on any thread; ExoPlayer is only touched on the main thread. The
 * sender polls [playbackInfo] from a native thread, so it reads a snapshot refreshed by
 * [ticker] every 500 ms.
 */
@OptIn(UnstableApi::class)
class CastPlayer(
    private val context: Context,
    private val view: PlayerView,
    private val onActiveChanged: (Boolean) -> Unit,
    private val onStats: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var failed = false
    private var volume = 1f

    @Volatile private var snapshot = NOT_READY

    private var lastRenderedFrames = 0
    private var lastTickMs = 0L

    fun play(url: String, startSeconds: Float) {
        main.post { start(url, startSeconds) }
    }

    fun seek(seconds: Float) {
        main.post { player?.seekTo((seconds * 1000).toLong()) }
    }

    /** AirPlay rate: 0 pauses, 1 plays. */
    fun setRate(rate: Float) {
        main.post { player?.playWhenReady = rate > 0f }
    }

    /** AirPlay volume in dB (-30..0, -144 = mute), applied to the cast's audio. */
    fun setVolume(db: Float) {
        val gain = if (db <= -30f) 0f else if (db >= 0f) 1f else Math.pow(10.0, db / 20.0).toFloat()
        main.post { player?.volume = gain; volume = gain }
    }

    fun togglePlayPause() {
        main.post { player?.let { it.playWhenReady = !it.playWhenReady } }
    }

    /** The sender stopped the cast. */
    fun stop() {
        main.post { release(finished = false) }
    }

    /** Stopped on the TV (Back on the remote): tell the sender the video is over. */
    fun stopFromTv() {
        main.post { release(finished = true) }
    }

    /** Must be called on the main thread. */
    fun releaseNow() = release(finished = false)

    /** [duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull], in seconds. */
    fun playbackInfo(): DoubleArray = snapshot

    private fun start(url: String, startSeconds: Float) {
        release(finished = false)
        failed = false
        Log.i(TAG, "cast ${redact(url)} from ${startSeconds}s")

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
        // Let a 50/60 fps stream switch the display mode where the TV can do it seamlessly.
        exo.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "cast playback failed: ${error.errorCodeName}", error)
                failed = true
            }
        })

        exo.volume = volume
        exo.setMediaItem(mediaItem(url))
        if (startSeconds > 0f) exo.seekTo((startSeconds * 1000).toLong())
        exo.playWhenReady = true
        exo.prepare()

        view.player = exo
        player = exo
        snapshot = NOT_READY
        lastRenderedFrames = 0
        lastTickMs = SystemClock.elapsedRealtime()
        onActiveChanged(true)
        main.postDelayed(ticker, TICK_MS)
    }

    private fun release(finished: Boolean) {
        main.removeCallbacks(ticker)
        val exo = player ?: return
        player = null
        view.player = null
        exo.release()
        snapshot = if (finished) FINISHED else NOT_READY
        onActiveChanged(false)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (failed || exo.playbackState == Player.STATE_ENDED) {
                release(finished = true)
                return
            }
            snapshot = snapshotOf(exo)
            onStats(statsOf(exo))
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun snapshotOf(exo: ExoPlayer): DoubleArray {
        val position = exo.currentPosition / 1000.0
        val started = exo.playbackState == Player.STATE_READY || exo.currentPosition > 0
        if (!started) return NOT_READY

        val buffered = exo.bufferedPosition / 1000.0
        var duration = if (exo.duration == C.TIME_UNSET) 0.0 else exo.duration / 1000.0
        // Live streams have no end; report the window so far so the sender never sees "finished".
        if (exo.isCurrentMediaItemLive || duration <= 0.0) duration = maxOf(duration, buffered, position)

        return doubleArrayOf(
            duration,
            position,
            0.0,
            duration,
            if (exo.playWhenReady) 1.0 else 0.0,
            if (exo.playbackState == Player.STATE_BUFFERING) 1.0 else 0.0,
            if (buffered - position > 10.0) 1.0 else 0.0,
        )
    }

    /** e.g. "Cast · 1080p · 59.9 fps (stream 60)". */
    private fun statsOf(exo: ExoPlayer): String {
        val now = SystemClock.elapsedRealtime()
        val counters = exo.videoDecoderCounters
        counters?.ensureUpdated()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val elapsed = (now - lastTickMs).coerceAtLeast(1)
        val fps = (rendered - lastRenderedFrames).coerceAtLeast(0) * 1000.0 / elapsed
        lastRenderedFrames = rendered
        lastTickMs = now

        val format = exo.videoFormat
        val resolution = format?.height?.takeIf { it > 0 }?.let { " · ${it}p" } ?: ""
        val streamFps = format?.frameRate?.takeIf { it > 0 }?.let { " (stream %.0f)".format(it) } ?: ""
        return "Cast$resolution · %.1f fps$streamFps".format(fps)
    }

    /** AirPlay senders mostly cast HLS; IPTV URLs often lack the .m3u8 extension. */
    private fun mediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val uri = Uri.parse(url)
        if (Util.inferContentType(uri) == C.CONTENT_TYPE_OTHER) {
            val lower = url.lowercase()
            if (listOf("m3u", "hls", "playlist", "/live").any { it in lower }) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        return builder.build()
    }

    /** Stream URLs can carry tokens; keep them out of logs. */
    private fun redact(url: String) = url.substringBefore('?')

    private companion object {
        const val TAG = "AirTV-cast"
        const val TICK_MS = 500L
        const val USER_AGENT = "AppleCoreMedia/1.0.0 (Apple TV; U; CPU OS 17_0 like Mac OS X; en_us)"

        val NOT_READY = doubleArrayOf(0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val FINISHED = doubleArrayOf(-1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }
}
