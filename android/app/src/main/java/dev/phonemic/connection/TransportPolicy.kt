package dev.phonemic.connection

import dev.phonemic.audio.Transport

/**
 * Which way to reach the laptop right now. When USB is the chosen route and the cable is
 * pulled out, fall back to Wi-Fi (if the address and code are set) or else Bluetooth; when the
 * cable is back, return to USB. Any other chosen route is used as it is.
 *
 * Pure function so it can be tested without a phone.
 */
object TransportPolicy {
    /** Unplugged this long before we switch, so a loose cable does not flip the stream. */
    const val DEBOUNCE_MS = 2000L

    fun pick(
        preferred: Int,
        current: Int,
        usbPlugged: Boolean,
        unpluggedForMs: Long,
        wifiReady: Boolean,
        bluetoothReady: Boolean,
    ): Int {
        if (preferred != Transport.USB) return preferred
        if (usbPlugged) return Transport.USB
        if (unpluggedForMs < DEBOUNCE_MS) return current
        return when {
            wifiReady -> Transport.WIFI
            bluetoothReady -> Transport.BLUETOOTH
            else -> Transport.USB       // nothing else to use: keep waiting for the cable
        }
    }
}
