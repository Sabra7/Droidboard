package dev.phonemic.connection

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import dev.phonemic.camera.CameraQuality
import dev.phonemic.screen.ScreenClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What the tablet or phone can decode: the laptop picks a picture size and speed it can handle. */
data class DecoderLimits(val maxWidth: Int, val maxHeight: Int, val fpsAtScreen: Int, val fps1080: Int, val lowLatency: Boolean)

data class CameraSpec(val facing: String, val maxWidth: Int, val maxHeight: Int)

/** Everything the app finds out about the device it runs on. Sent to the laptop and used to hide what does not exist. */
data class DeviceProfile(
    val id: String,
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val refreshHz: Int,
    val supportedHz: List<Int>,
    val multiTouch: Boolean,
    val avc: DecoderLimits?,
    val hevc: Boolean,
    val cameras: List<CameraSpec>,
) {
    fun hasCamera(facing: String) = cameras.any { it.facing == facing }

    /** The biggest picture the camera of that side can give, or null when there is none. */
    fun cameraMax(facing: String): Pair<Int, Int>? =
        cameras.filter { it.facing == facing }.maxByOrNull { it.maxWidth.toLong() * it.maxHeight }
            ?.let { it.maxWidth to it.maxHeight }

    /** Which camera sizes this device offers on that side. */
    fun qualities(facing: String): List<CameraQuality> {
        val max = cameraMax(facing) ?: return emptyList()
        return CameraQuality.values().filter { it.width <= maxOf(max.first, max.second) && it.height <= minOf(max.first, max.second) }
            .ifEmpty { listOf(CameraQuality.HD) }
    }

    /** The part every connection says about itself: who is calling. */
    fun idJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    /** The whole profile, for the second screen. */
    fun toJson(): JSONObject = idJson()
        .put("w", screenWidth).put("h", screenHeight).put("dpi", densityDpi)
        .put("hz", refreshHz).put("modes", JSONArray(supportedHz))
        .put("multitouch", multiTouch)
        .put("hevc", hevc)
        .apply {
            avc?.let {
                put("avc", JSONObject().put("w", it.maxWidth).put("h", it.maxHeight).put("fps", it.fpsAtScreen)
                    .put("fps1080", it.fps1080).put("lowLatency", it.lowLatency))
            }
            put("cameras", JSONArray().apply {
                cameras.forEach { put(JSONObject().put("facing", it.facing).put("w", it.maxWidth).put("h", it.maxHeight)) }
            })
        }
}

object DeviceProfiles {
    @Volatile private var cached: DeviceProfile? = null
    @Volatile private var appContext: Context? = null

    /** Call once at start: after this [hello] works from any thread. */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun get(ctx: Context): DeviceProfile = cached ?: synchronized(this) {
        cached ?: collect(ctx.applicationContext).also { cached = it }
    }

    /** Who is calling: every connection to the laptop says it, so the laptop can list its devices. */
    fun hello(): JSONObject {
        val id = appContext?.let { installId(it) } ?: ""
        return JSONObject().put("id", id).put("name", deviceName())
    }

    /** A random id made once and kept: the laptop tells devices apart by it, even two of the same model. */
    fun installId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    private fun collect(ctx: Context): DeviceProfile {
        val (w, h) = ScreenClient.screenSize(ctx).let { it[0] to it[1] }
        val (hz, modes) = refreshRates(ctx)
        val pm = ctx.packageManager
        return DeviceProfile(
            id = installId(ctx), name = deviceName(),
            screenWidth = w, screenHeight = h, densityDpi = ctx.resources.displayMetrics.densityDpi,
            refreshHz = hz, supportedHz = modes,
            multiTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH),
            avc = runCatching { decoderLimits("video/avc", w, h) }.getOrNull(),
            hevc = runCatching { decoderLimits("video/hevc", w, h) != null }.getOrDefault(false),
            cameras = runCatching { cameras(ctx) }.getOrDefault(emptyList()),
        )
    }

    @Suppress("DEPRECATION")
    private fun refreshRates(ctx: Context): Pair<Int, List<Int>> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = wm.defaultDisplay
        val now = Math.round(display.refreshRate).coerceAtLeast(30)
        val modes = display.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted()
        return now to modes.ifEmpty { listOf(now) }
    }

    /** The best (hardware first) decoder for [mime]: how big and how fast it goes. Null when there is none. */
    private fun decoderLimits(mime: String, screenW: Int, screenH: Int): DecoderLimits? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        val best = infos.sortedByDescending { if (Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 1 else 0 }.firstOrNull()
            ?: return null
        val caps = best.getCapabilitiesForType(mime)
        val v = caps.videoCapabilities ?: return null
        val lowLatency = Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        fun fps(w: Int, h: Int): Int = runCatching {
            if (v.isSizeSupported(w, h)) v.getSupportedFrameRatesFor(w, h).upper.toInt() else 0
        }.getOrDefault(0)
        return DecoderLimits(v.supportedWidths.upper, v.supportedHeights.upper, fps(screenW, screenH), fps(1920, 1080), lowLatency)
    }

    private fun cameras(ctx: Context): List<CameraSpec> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.mapNotNull { id ->
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                else -> return@mapNotNull null            // external cameras: not offered
            }
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            val max = sizes?.maxByOrNull { it.width.toLong() * it.height } ?: return@mapNotNull null
            CameraSpec(facing, max.width, max.height)
        }
    }
}
