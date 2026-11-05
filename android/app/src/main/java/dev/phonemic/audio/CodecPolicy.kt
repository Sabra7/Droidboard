package dev.phonemic.audio

/** What goes over the link: Opus or PCM, and the PCM down-sampling factor (3 = 16 kHz for Bluetooth). */
data class CodecChoice(val opus: Boolean, val decim: Int, val note: String)

/** Pure rules for choosing the codec. USB stays PCM; Bluetooth wants Opus; Wi-Fi only if the user asks. */
object CodecPolicy {
    const val MONO_BITRATE = 64_000
    const val STEREO_BITRATE = 96_000

    fun bitrate(channels: Int) = if (channels >= 2) STEREO_BITRATE else MONO_BITRATE

    /**
     * [transport] is the way this link goes over (Transport.*). [encoderAvailable]: the phone has a
     * working Opus encoder. [laptopOpus]: the laptop said it can decode Opus.
     */
    fun pick(transport: Int, compressWifi: Boolean, encoderAvailable: Boolean, laptopOpus: Boolean): CodecChoice {
        val wanted = when (transport) {
            Transport.BLUETOOTH -> true
            Transport.WIFI -> compressWifi
            else -> false
        }
        if (!wanted) {
            return CodecChoice(false, if (transport == Transport.BLUETOOTH) 3 else 1, "PCM")
        }
        val pcmDecim = if (transport == Transport.BLUETOOTH) 3 else 1
        val pcmName = if (transport == Transport.BLUETOOTH) "PCM 16 kHz" else "PCM"
        return when {
            !encoderAvailable -> CodecChoice(false, pcmDecim, "$pcmName (this phone has no Opus encoder)")
            !laptopOpus -> CodecChoice(false, pcmDecim, "$pcmName (the laptop app cannot decode Opus, see README)")
            else -> CodecChoice(true, 1, "Opus")
        }
    }
}
