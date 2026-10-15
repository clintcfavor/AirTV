package dev.airtv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Plays AirPlay audio: AAC-ELD (screen mirroring) and AAC-LC are decoded with
 * MediaCodec; ALAC (music) arrives already decoded to PCM by the native layer.
 */
class AudioRenderer {
    private sealed class Packet {
        class Compressed(val data: ByteArray, val type: Int, val ptsUs: Long) : Packet()
        class Pcm(val data: ByteArray) : Packet()
        object Flush : Packet()
    }

    private val queue = LinkedBlockingQueue<Packet>(MAX_QUEUED)
    @Volatile private var running = false
    @Volatile private var gain = 1f
    private var thread: Thread? = null

    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var codecType = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private var pcmScratch = ByteArray(0)

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "AirTV-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
        queue.clear()
    }

    fun submitCompressed(data: ByteArray, type: Int, ptsUs: Long) = offer(Packet.Compressed(data, type, ptsUs))

    fun submitPcm(data: ByteArray) = offer(Packet.Pcm(data))

    fun flush() {
        queue.clear()
        offer(Packet.Flush)
    }

    /** AirPlay volume is -30..0 dB, with -144 meaning mute. */
    fun setVolume(db: Float) {
        gain = when {
            db <= -144f -> 0f
            db <= -30f -> 0f
            db >= 0f -> 1f
            else -> 10f.pow(db / 20f)
        }
        track?.setVolume(gain)
    }

    private fun offer(packet: Packet) {
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
        }
    }

    private fun loop() {
        try {
            while (running) {
                when (val packet = queue.poll(20, TimeUnit.MILLISECONDS)) {
                    is Packet.Compressed -> decode(packet)
                    is Packet.Pcm -> write(packet.data, packet.data.size)
                    Packet.Flush -> {
                        track?.pause()
                        track?.flush()
                        track?.play()
                        runCatching { codec?.flush() }
                    }
                    null -> {}
                }
                drain()
            }
        } catch (_: InterruptedException) {
        } finally {
            releaseCodec()
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
        }
    }

    private fun decode(packet: Packet.Compressed) {
        if (codecType != packet.type) {
            releaseCodec()
            createCodec(packet.type)
        }
        val codec = codec ?: return
        try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(packet.data)
            codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs, 0)
        } catch (e: Exception) {
            Log.e(TAG, "audio decoder error", e)
            releaseCodec()
        }
    }

    private fun drain() {
        val codec = codec ?: return
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (index < 0) return
                val out = codec.getOutputBuffer(index)
                if (out != null && bufferInfo.size > 0) {
                    if (pcmScratch.size < bufferInfo.size) pcmScratch = ByteArray(bufferInfo.size)
                    out.position(bufferInfo.offset)
                    out.get(pcmScratch, 0, bufferInfo.size)
                    write(pcmScratch, bufferInfo.size)
                }
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio decoder error while draining", e)
            releaseCodec()
        }
    }

    private fun write(data: ByteArray, size: Int) {
        val track = track ?: createTrack()
        track.write(data, 0, size)
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        created.setVolume(gain)
        created.play()
        track = created
        return created
    }

    private fun createCodec(type: Int) {
        // AudioSpecificConfig for the formats AirPlay advertises (44.1 kHz stereo).
        val (profile, config) = when (type) {
            TYPE_AAC_ELD -> MediaCodecInfo.CodecProfileLevel.AACObjectELD to byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
            TYPE_AAC_LC -> MediaCodecInfo.CodecProfileLevel.AACObjectLC to byteArrayOf(0x12, 0x10)
            else -> {
                Log.w(TAG, "unsupported audio compression type $type")
                codecType = type
                return
            }
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setByteBuffer("csd-0", ByteBuffer.wrap(config))
        }
        try {
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            created.configure(format, null, null, 0)
            created.start()
            codec = created
            Log.i(TAG, "started AAC decoder ${created.name} for type $type")
        } catch (e: Exception) {
            Log.e(TAG, "could not start AAC decoder for type $type", e)
            codec = null
        }
        codecType = type
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        codecType = -1
    }

    private companion object {
        const val TAG = "AirTV-audio"
        const val SAMPLE_RATE = 44100
        const val MAX_QUEUED = 24 // ~250 ms of AAC-ELD; older audio is dropped rather than played late
        const val INPUT_TIMEOUT_US = 20_000L
        const val TYPE_AAC_LC = 4
        const val TYPE_AAC_ELD = 8
    }
}
