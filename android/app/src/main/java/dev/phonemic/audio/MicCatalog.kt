package dev.phonemic.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MicrophoneInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException

/** One row of the Mic sheet. [detail] is a short hint under the name. */
data class MicOption(val slot: MicSlot, val detail: String)

/** Asks Android which microphones exist (the part of mic selection that needs the framework). */
object MicCatalog {

    /** Built-in mics, as plain data. Empty below Android 9 or when the phone will not say. */
    fun builtIn(ctx: Context): List<BuiltInMic> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.microphones.filter { it.location != MicrophoneInfo.LOCATION_PERIPHERAL }.map { it.toPlain() }
        } catch (_: IOException) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun MicrophoneInfo.toPlain(): BuiltInMic {
        val p = position
        val o = orientation
        return BuiltInMic(id.toString(), description, p.x, p.y, p.z, o.x, o.y, o.z)
    }

    private val EXTERNAL_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /** Microphones plugged in or paired right now (wired headset, USB-C, Bluetooth headset). */
    fun external(ctx: Context): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in EXTERNAL_TYPES }
    }

    /** What the sheet should offer: Auto always, then only the mics this phone really has. */
    fun options(ctx: Context): List<MicOption> {
        val list = mutableListOf(MicOption(MicSlot.AUTO, "The phone decides"))
        val mics = builtIn(ctx)
        val slots = MicSelection.classify(mics)
        for (slot in listOf(MicSlot.BOTTOM, MicSlot.TOP, MicSlot.BACK)) {
            val m = mics.firstOrNull { slots[it.id] == slot } ?: continue
            list += MicOption(slot, m.description.ifBlank { "Built-in microphone" })
        }
        external(ctx).firstOrNull()?.let {
            list += MicOption(MicSlot.EXTERNAL, it.productName.toString())
        }
        return list
    }

    /** Applies the user's pick to a recorder that has not started yet. */
    fun applyChoice(ctx: Context, record: AudioRecord, choice: MicSlot) {
        when (choice) {
            MicSlot.AUTO -> {}
            MicSlot.EXTERNAL -> external(ctx).firstOrNull()?.let { record.preferredDevice = it }
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record.setPreferredMicrophoneDirection(MicSelection.directionFor(choice))
            }
        }
    }

    /**
     * After recording started: which mic does Android say is in use? Updates
     * [MicState.activeMic] and [MicState.micNote].
     */
    @SuppressLint("NewApi")      // `active` is empty below Android 10, so its MicrophoneInfo calls never run there
    fun reportActive(ctx: Context, record: AudioRecord, choice: MicSlot) {
        val slots = MicSelection.classify(builtIn(ctx))
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { record.activeMicrophones } catch (_: Exception) { emptyList() }
        } else emptyList()
        val activeSlots = active.mapNotNull { slots[it.id.toString()] }.toSet()
        val routed = record.routedDevice
        val usingExternal = routed != null && routed.type in EXTERNAL_TYPES

        MicState.activeMic = when {
            usingExternal -> routed!!.productName.toString()
            activeSlots.isNotEmpty() -> activeSlots.joinToString(" + ") { it.label }
            active.isNotEmpty() -> active.first().description.ifBlank { "Built-in microphone" }
            else -> "Built-in microphone"
        }
        MicState.micNote = MicSelection.mismatchNote(choice, activeSlots, usingExternal) ?: ""
    }
}
