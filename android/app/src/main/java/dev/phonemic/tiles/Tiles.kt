package dev.phonemic.tiles

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.phonemic.R
import dev.phonemic.audio.MicService
import dev.phonemic.audio.MicState
import dev.phonemic.audio.Transport

/** Quick Settings tile "Droidboard": tap to start / stop, shows the way and "Muted". */
class DroidboardTile : TileService() {

    companion object {
        /** Asks the system to redraw the tile (it only does so while the shade is open). */
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, DroidboardTile::class.java))
                requestListeningState(ctx, ComponentName(ctx, MuteTile::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onStartListening() = redraw()

    private fun redraw() {
        if (!MicState.running) MicState.load(this)
        val way = if (MicState.running) MicState.active else MicState.transport
        val info = TileModel.info(
            MicState.running, MicState.muted,
            MicState.status.startsWith("Connected"), Transport.NAMES[way],
        )
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mic)
        tile.label = "Droidboard"
        tile.state = if (info.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = info.subtitle
        tile.updateTile()
    }

    override fun onClick() {
        if (MicState.running) {
            MicState.running = false
            stopService(Intent(this, MicService::class.java))
            redraw()
        } else {
            // Android 14 refuses a microphone foreground service started from the background.
            // A transparent activity is "in the foreground", so it may start it, then closes itself.
            val intent = Intent(this, TileStartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}

/** Second, optional tile: mute / unmute only. */
class MuteTile : TileService() {

    override fun onStartListening() = redraw()

    private fun redraw() {
        if (!MicState.running) MicState.load(this)
        val info = TileModel.muteInfo(MicState.running, MicState.muted)
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mic)
        tile.label = "Mute"
        tile.state = if (info.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = info.subtitle
        tile.updateTile()
    }

    override fun onClick() {
        if (MicState.running) MicState.muted = !MicState.muted   // the service picks it up
        redraw()
    }
}
