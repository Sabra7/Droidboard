package dev.phonemic.audio

/**
 * Now Bar choices in Connection.
 *
 * Clean is a plain Android 16 Live Update. One UI puts it in the Now Bar only when Developer options >
 * "Live notifications for all apps" is on; without it Samsung shows a normal notification. The other way in,
 * Samsung's "android.ongoingActivityNoti.automation" extra, is not used: One UI treats it as Gemini acting in
 * the app and draws a moving colored border (and sometimes a Gemini label) around the card.
 */
object NowBarMode {
    const val CLEAN = 0       // a Live Update: status bar chip, and the Now Bar where the phone allows it
    const val OFF = 1         // not promoted at all: status bar icon and the notification shade only
    val NAMES = listOf("Clean", "Off")
}

/** What the ongoing notification (Now Bar, status bar chip, Live notification) shows. Pure, so it can be tested. */
data class NowBarInfo(val title: String, val chip: String)

object NowBarModel {
    /**
     * The service's status flips between "Connection lost, retrying…", "Waiting for the laptop app…" and
     * "Switching connection…" while it reconnects. Each new text re-posts the notification, and the Now Bar
     * redraws (and flashes) every time, so all of those show as one steady "Waiting for the laptop".
     * Messages that ask the user to do something stay as they are.
     */
    fun info(status: String, muted: Boolean): NowBarInfo = when {
        muted -> NowBarInfo("Muted", "Muted")
        status.startsWith("Connected") -> NowBarInfo("Connected", "Live")
        waiting(status) -> NowBarInfo("Waiting for the laptop", "Wait")
        else -> NowBarInfo(status, "Off")
    }

    /** Anything but an explicit Off (junk in prefs included) is Clean. */
    fun mode(choice: Int): Int = if (choice == NowBarMode.OFF) NowBarMode.OFF else NowBarMode.CLEAN

    private fun waiting(status: String) =
        status.startsWith("Waiting for the laptop") || status.startsWith("Connecting") ||
            status.startsWith("Connection lost") || status.startsWith("Switching connection")
}
