package dev.airtv

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import kotlin.random.Random

class MainActivity : Activity(), AirPlayListener {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var idleView: View
    private lateinit var nameView: TextView
    private lateinit var hintView: TextView
    private lateinit var addressView: TextView
    private lateinit var statusView: TextView
    private lateinit var nowPlayingView: TextView
    private lateinit var fpsView: TextView
    private var showFps = true

    private lateinit var advertiser: ServiceAdvertiser
    private val audio = AudioRenderer()
    private lateinit var video: VideoRenderer

    private var receiverName = ""
    private var videoWidth = 0
    private var videoHeight = 0
    @Volatile private var mirroring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Ask the TV for its low-latency ("game") picture mode: skips motion smoothing etc.
            window.setPreferMinimalPostProcessing(true)
        }
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        idleView = findViewById(R.id.idle)
        nameView = findViewById(R.id.receiver_name)
        hintView = findViewById(R.id.hint)
        addressView = findViewById(R.id.address)
        statusView = findViewById(R.id.status)
        nowPlayingView = findViewById(R.id.now_playing)
        fpsView = findViewById(R.id.fps)
        showFps = getSharedPreferences("airtv", Context.MODE_PRIVATE).getBoolean("show_fps", true)

        advertiser = ServiceAdvertiser(this)
        video = VideoRenderer(
            onVideoSize = { w, h -> runOnUiThread { videoWidth = w; videoHeight = h; layoutSurface() } },
            onFirstFrame = { runOnUiThread { showMirroring(true) } },
            onStats = { fps, latencyMs ->
                runOnUiThread { fpsView.text = getString(R.string.fps_counter, fps, latencyMs) }
            },
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = video.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = video.setSurface(null)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }

        receiverName = receiverName()
        nameView.text = receiverName
        hintView.text = getString(R.string.hint, receiverName)
    }

    override fun onStart() {
        super.onStart()
        video.start()
        audio.start()
        startReceiver()
    }

    override fun onStop() {
        AirPlayNative.nativeStop()
        advertiser.unregisterAll()
        video.stop()
        audio.stop()
        showMirroring(false)
        super.onStop()
    }

    private fun startReceiver() {
        val metrics = resources.displayMetrics
        val width = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtMost(1920)
        val height = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtMost(1080)
        val keyFile = File(filesDir, "airplay-key.pem").absolutePath
        val port = AirPlayNative.nativeStart(this, receiverName, deviceId(), keyFile, width, height, 60)
        if (port < 0) {
            Log.e(TAG, "receiver failed to start: $port")
            statusView.text = getString(R.string.status_failed, port)
        } else {
            statusView.text = getString(R.string.status_ready)
        }
        addressView.text = localAddress()?.let { getString(R.string.address, it, port) } ?: getString(R.string.no_network)
    }

    private fun showMirroring(active: Boolean) {
        mirroring = active
        idleView.visibility = if (active) View.GONE else View.VISIBLE
        fpsView.visibility = if (active && showFps) View.VISIBLE else View.GONE
        if (active) nowPlayingView.visibility = View.GONE
    }

    /** OK / Enter on the remote toggles the FPS counter. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            showFps = !showFps
            getSharedPreferences("airtv", Context.MODE_PRIVATE).edit().putBoolean("show_fps", showFps).apply()
            fpsView.visibility = if (mirroring && showFps) View.VISIBLE else View.GONE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun layoutSurface() {
        if (videoWidth <= 0 || videoHeight <= 0 || root.width == 0) return
        val scale = minOf(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surfaceView.layoutParams = lp
        }
    }

    private fun receiverName(): String {
        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        return (deviceName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV").take(40)
    }

    /** A stable, locally administered MAC-style id; AirPlay clients key pairing on it. */
    private fun deviceId(): String {
        val prefs = getSharedPreferences("airtv", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val bytes = Random.nextBytes(6)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        val id = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    private fun localAddress(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }

    // ---- AirPlayListener (called from native threads) ----

    override fun onRegisterService(service: Int, name: String, type: String, port: Int, keys: Array<String>, values: Array<String>) =
        advertiser.register(service, name, type, port, keys, values)

    override fun onUnregisterService(service: Int) = advertiser.unregister(service)

    override fun onConnection(active: Boolean) {
        Log.i(TAG, "connection active=$active")
        if (!active) {
            video.reset()
            audio.flush()
            runOnUiThread {
                showMirroring(false)
                nowPlayingView.visibility = View.GONE
            }
        }
    }

    override fun onVideoCodec(isH265: Boolean) {
        Log.i(TAG, "video codec ${if (isH265) "H.265" else "H.264"}")
    }

    override fun onVideoData(data: ByteArray, isH265: Boolean, ptsUs: Long) = video.submit(data, isH265, ptsUs)

    override fun onVideoReset() {
        video.reset()
        runOnUiThread { showMirroring(false) }
    }

    override fun onAudioFormat(compressionType: Int) {
        Log.i(TAG, "audio compression type $compressionType")
    }

    override fun onAudioCompressed(data: ByteArray, compressionType: Int, ptsUs: Long) =
        audio.submitCompressed(data, compressionType, ptsUs)

    override fun onAudioPcm(data: ByteArray, ptsUs: Long) = audio.submitPcm(data)

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) = audio.setVolume(db)

    override fun onMetadata(title: String, artist: String, album: String) {
        val text = listOf(title, artist, album).filter { it.isNotBlank() }.joinToString("\n")
        runOnUiThread {
            nowPlayingView.text = text
            nowPlayingView.visibility = if (text.isNotEmpty() && !mirroring) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val TAG = "AirTV"
    }
}
