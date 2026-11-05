package dev.phonemic.tiles

/** What the "Droidboard" Quick Settings tile shows. Pure, so it can be tested. */
data class TileInfo(val active: Boolean, val subtitle: String)

object TileModel {
    /**
     * [transportName] is the way in use (or the chosen one when stopped). Muted still counts as
     * "on": the mic is streaming, only silence is sent.
     */
    fun info(running: Boolean, muted: Boolean, connected: Boolean, transportName: String): TileInfo = when {
        !running -> TileInfo(false, transportName)
        muted -> TileInfo(true, "Muted")
        !connected -> TileInfo(true, "$transportName · waiting")
        else -> TileInfo(true, transportName)
    }

    /** The second tile (mute only). Inactive while the mic is not streaming, so it is not a trap. */
    fun muteInfo(running: Boolean, muted: Boolean): TileInfo = when {
        !running -> TileInfo(false, "Mic is off")
        muted -> TileInfo(true, "Muted")
        else -> TileInfo(false, "Live")
    }
}
