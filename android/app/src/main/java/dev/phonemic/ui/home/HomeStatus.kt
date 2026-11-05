package dev.phonemic.ui.home

import dev.phonemic.audio.NowBarModel
import dev.phonemic.audio.Transport

/** The four looks of the status capsule on Home: the icon color, and whether the level wave shows. */
enum class CapsuleState { LIVE, WAIT, MUTED, OFF }

data class CapsuleInfo(val state: CapsuleState, val title: String, val subtitle: String)

/** What the status capsule on Home says. Pure, so it can be tested. */
object HomeStatus {
    fun info(
        running: Boolean, muted: Boolean, status: String,
        transport: Int, codecNote: String, channels: Int,
    ): CapsuleInfo {
        val way = Transport.NAMES[transport.coerceIn(0, Transport.NAMES.size - 1)]
        return when {
            !running -> CapsuleInfo(CapsuleState.OFF, "Stopped", "Tap Start to stream")
            muted -> CapsuleInfo(CapsuleState.MUTED, "Muted", "$way · the laptop hears silence")
            status.startsWith("Connected") -> CapsuleInfo(
                CapsuleState.LIVE, "Connected",
                // PCM is the normal case and says nothing to the user; Opus is named while it is on
                listOfNotNull(way, if (codecNote.startsWith("Opus")) "Opus" else null,
                    if (channels == 2) "stereo" else "mono").joinToString(" · "),
            )
            else -> {
                // the reconnect statuses collapse to one steady title, like the notification
                val title = NowBarModel.info(status, muted = false).title
                CapsuleInfo(CapsuleState.WAIT, title, if (title == status) way else "$way · ${hint(transport)}")
            }
        }
    }

    private fun hint(transport: Int) = when (transport) {
        Transport.WIFI -> "same network as the laptop"
        Transport.BLUETOOTH -> "paired with the laptop"
        else -> "open the Windows app"
    }
}
