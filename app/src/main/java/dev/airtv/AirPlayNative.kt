package dev.airtv

/**
 * Callbacks from the native AirPlay core. These arrive on native network threads,
 * never on the main thread. Method names and signatures are looked up from JNI.
 */
interface AirPlayListener {
    fun onRegisterService(service: Int, name: String, type: String, port: Int, keys: Array<String>, values: Array<String>)
    fun onUnregisterService(service: Int)
    fun onConnection(active: Boolean)
    fun onVideoCodec(isH265: Boolean)
    fun onVideoData(data: ByteArray, isH265: Boolean, ptsUs: Long)
    fun onVideoReset()
    fun onAudioFormat(compressionType: Int)
    fun onAudioCompressed(data: ByteArray, compressionType: Int, ptsUs: Long)
    fun onAudioPcm(data: ByteArray, ptsUs: Long)
    fun onAudioFlush()
    fun onVolume(db: Float)
    fun onMetadata(title: String, artist: String, album: String)
}

object AirPlayNative {
    init {
        System.loadLibrary("airtv")
    }

    /** Returns the TCP port the receiver listens on, or a negative error code. */
    @JvmStatic
    external fun nativeStart(
        listener: AirPlayListener,
        name: String,
        deviceId: String,
        keyFile: String,
        width: Int,
        height: Int,
        fps: Int,
    ): Int

    @JvmStatic
    external fun nativeStop()
}
