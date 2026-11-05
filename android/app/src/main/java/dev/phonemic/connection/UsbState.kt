package dev.phonemic.connection

import android.content.Context
import android.content.IntentFilter

/** True while a USB data cable is attached (sticky broadcast, needs no permission). */
fun Context.isUsbPlugged(): Boolean =
    registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        ?.getBooleanExtra("connected", false) == true
