package dev.phonemic.connection

import java.nio.ByteBuffer

/**
 * Wire protocol, little-endian. Keep in sync with windows/audio/protocol.py.
 *
 * magic "PMIC" | version u8 | flags u8 | codec u8 | channels u8 |
 * seq u32 | timestamp_ns u64 | sample_rate u32 | payload_len u16
 */
object Protocol {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte())
    const val VERSION = 1
    const val CODEC_PCM16 = 0
    const val CODEC_OPUS = 1        // one Opus packet per frame
    const val CAPS_SIZE = 6         // laptop -> phone right after connecting: "PMCP", version, flags
    private const val CAP_OPUS = 1
    const val FLAG_MUTED = 0x01
    const val HEADER_SIZE = 26

    /** Writes the header at the buffer's position. The buffer must be little-endian. */
    fun writeHeader(
        b: ByteBuffer,
        seq: Long,
        flags: Int,
        timestampNs: Long,
        sampleRate: Int,
        payloadLen: Int,
        channels: Int = 1,      // 2 = interleaved L R L R ...
        codec: Int = CODEC_PCM16,
    ) {
        b.put(MAGIC)
        b.put(VERSION.toByte())
        b.put(flags.toByte())
        b.put(codec.toByte())
        b.put(channels.toByte())
        b.putInt((seq and 0xFFFFFFFFL).toInt())
        b.putLong(timestampNs)
        b.putInt(sampleRate)
        b.putShort(payloadLen.toShort())
    }

    /** Does the laptop's capabilities message say it can decode Opus? (null / wrong message: no) */
    fun capsHaveOpus(caps: ByteArray?): Boolean =
        caps != null && caps.size >= CAPS_SIZE &&
            caps[0] == 'P'.code.toByte() && caps[1] == 'M'.code.toByte() &&
            caps[2] == 'C'.code.toByte() && caps[3] == 'P'.code.toByte() &&
            (caps[5].toInt() and CAP_OPUS) != 0
}
