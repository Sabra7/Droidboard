package dev.phonemic.tiles

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dev.phonemic.MainActivity
import dev.phonemic.audio.MicService
import dev.phonemic.audio.MicState

/**
 * Invisible activity that starts the streaming service for the Quick Settings tile and closes
 * itself. Being a visible activity is what lets Android 14 accept a microphone foreground service.
 */
class TileStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // no microphone permission yet: the main screen asks for it
            startActivity(Intent(this, MainActivity::class.java))
        } else if (!MicState.running) {
            MicState.load(this)
            startForegroundService(Intent(this, MicService::class.java))
        }
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
