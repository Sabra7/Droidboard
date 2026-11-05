package dev.phonemic.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Opus through the phone's own MediaCodec encoder ("audio/opus", Android 10+). Input is 48 kHz
 * 16-bit PCM (interleaved when stereo); output is one Opus packet per call or later.
 * The codec cuts 20 ms packets, so a 10 ms frame in often gives nothing out, then a packet.
 */
class OpusEncoder private constructor(
    private val codec: MediaCodec,
    val channels: Int,
) {
    companion object {
        private const val MIME = "audio/opus"
        private const val RATE = 48000

        /** Creates a started encoder, or null when this phone cannot make one. */
        fun create(channels: Int, bitrate: Int): OpusEncoder? {
            var codec: MediaCodec? = null
            return try {
                val format = MediaFormat.createAudioFormat(MIME, RATE, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
                codec = MediaCodec.createEncoderByType(MIME)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                OpusEncoder(codec, channels)
            } catch (e: Exception) {
                try { codec?.release() } catch (_: Exception) {}
                null
            }
        }

        private var probed: Boolean? = null

        /** Can this phone encode Opus at all? Tried once (a real encoder is created and dropped). */
        fun isAvailable(): Boolean = probed ?: (create(1, CodecPolicy.MONO_BITRATE)?.also { it.close() } != null)
            .also { probed = it }
    }

    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    /** Feeds one frame of PCM ([pcm] holds samples for all channels) and hands every finished packet to [onPacket]. */
    fun encode(pcm: ShortArray, onPacket: (ByteArray) -> Unit) {
        val i = codec.dequeueInputBuffer(20_000)
        if (i >= 0) {
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            codec.queueInputBuffer(i, 0, pcm.size * 2, ptsUs, 0)
            ptsUs += pcm.size / channels * 1_000_000L / RATE
        }
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (o < 0) continue                                  // format / buffers changed: nothing to send
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val out = codec.getOutputBuffer(o)!!
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                out.get(bytes)
                onPacket(bytes)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    fun close() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
