package dev.phonemic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.phonemic.audio.MicService
import dev.phonemic.audio.MicState
import dev.phonemic.audio.Transport
import dev.phonemic.connection.DeviceProfiles
import dev.phonemic.connection.LaptopFinder
import dev.phonemic.ui.DroidboardTheme
import dev.phonemic.ui.home.MainScreen

/** Start/stop, mute, mic gain, status and a level meter (UI in ui/MainScreen.kt). */
class MainActivity : ComponentActivity() {

    private companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MicState.load(this)
        DeviceProfiles.init(this)
        Thread { DeviceProfiles.get(this) }.start()       // reads the screen, decoders and cameras once, off the main thread
        setContent {
            DroidboardTheme {
                MainScreen(onToggle = ::toggleStreaming, onTransportChanged = ::transportChanged)
            }
        }
        askPermissions()
        handleAutostart(intent)
    }

    override fun onStart() {
        super.onStart()
        MicState.autoStartHeld = false  // back on screen: Start when the laptop is found may act again
        LaptopFinder.start(this)        // the laptop's Wi-Fi address changes between networks
    }

    override fun onStop() {
        LaptopFinder.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutostart(intent)
    }

    /**
     * The laptop app runs `am start ... --ez autostart true` when the cable goes in: start
     * streaming (unless the user turned that off) and step back, so the screen is left alone.
     * An activity that is on screen may start a microphone foreground service on Android 14.
     */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)         // a recreated activity must not repeat it
        if (!MicState.allowRemoteStart) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!MicState.running) startForegroundService(Intent(this, MicService::class.java))
        handler.postDelayed({ moveTaskToBack(true) }, 400)
    }

    private fun toggleStreaming() {
        val intent = Intent(this, MicService::class.java)
        if (MicState.running) {
            MicState.running = false
            stopService(intent)
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                askPermissions()
                return
            }
            startForegroundService(intent)
        }
    }

    /** The user picked another way to connect: ask for Bluetooth access, restart if running. */
    private fun transportChanged() {
        if (MicState.transport == Transport.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
        }
        if (MicState.running) {
            val intent = Intent(this, MicService::class.java)
            MicState.running = false
            stopService(intent)
            handler.postDelayed({ startForegroundService(intent) }, 400)
        }
    }

    private fun askPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
