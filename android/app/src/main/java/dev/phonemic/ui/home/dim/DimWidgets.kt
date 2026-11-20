package dev.phonemic.ui.home.dim

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.mutableIntStateOf
import dev.phonemic.deck.LaptopStats
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.camera.CameraUi
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a widget needs to know about the stream, and whether it is only a picture in the editor. */
class DimEnv(
    val live: Boolean,
    val muted: Boolean,
    val onMute: () -> Unit,
    /** In the editor: samples instead of live data, no camera, no taps. */
    val preview: Boolean = false,
)

private val QuietText = Color(0xFFC8C8C8)

internal fun DimWidget.ink(default: Color = QuietText): Color = color?.let { Color(it) } ?: default

internal fun dimFamily(style: String): FontFamily = when (style) {
    DimFont.MONO -> FontFamily.Monospace
    DimFont.SERIF -> FontFamily.Serif
    else -> FontFamily.Default
}

/** Weight 0 Light, 1 Regular, 2 SemiBold. Unset: Light for the Light style, else what the widget type likes. */
internal fun DimWidget.weightIndex(): Int =
    option("weight", if (fontStyle == DimFont.LIGHT) 0 else when (type) {
        DimWidgetType.CLOCK -> 0
        DimWidgetType.MUTE -> 2
        else -> 1
    })

internal fun DimWidget.fontWeight(): FontWeight = when (weightIndex()) {
    0 -> FontWeight.Light
    1 -> FontWeight.Normal
    else -> FontWeight.SemiBold
}

/** One widget, drawn to fill the box it is given. */
@Composable
fun DimWidgetView(w: DimWidget, env: DimEnv, modifier: Modifier = Modifier) {
    when (w.type) {
        DimWidgetType.MUTE -> MuteWidget(w, env, modifier)
        DimWidgetType.CLOCK -> ClockWidget(w, modifier)
        DimWidgetType.STATS -> StatsWidget(w, env, modifier)
        DimWidgetType.CAMERA -> CameraWidget(w, env, modifier)
    }
}

// ---- Mute ------------------------------------------------------------------------------------------

@Composable
private fun MuteWidget(w: DimWidget, env: DimEnv, modifier: Modifier) {
    val muted = env.muted
    val base = w.color?.let { Color(it) }
    val text = if (muted) Color(0xFFFF9A90) else base ?: QuietText
    val edge = if (muted) Color(0xFF8A3A3A) else base?.copy(0.55f) ?: Color(0xFF3A3A3A)
    val fill = if (muted) Color(0xFF3A1212) else base?.copy(0.12f) ?: Color(0xFF141414)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight * 0.78f) * 0.92f       // the circle; 168 dp on the default layout
        val label = (d.value * 0.119f * w.fontScale).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(d).clip(CircleShape).background(fill).border(1.5.dp, edge, CircleShape)
                    .then(if (env.preview) Modifier else Modifier.clickable(role = Role.Button, onClick = env.onMute)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d * 0.06f)) {
                    DeckIcon(if (muted) "mic-off" else "mic", text, d * 0.24f)
                    Text(if (muted) "Unmute" else "Mute", fontSize = label, fontWeight = w.fontWeight(),
                        fontFamily = dimFamily(w.fontStyle), color = text, maxLines = 1)
                }
            }
            Spacer(Modifier.height(d * 0.1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // tiny status light: dim green = streaming, dim red = muted, grey = not connected
                Box(Modifier.size(6.dp).background(
                    if (env.live && !muted) Color(0xFF138A3A) else if (muted) Color(0xFF8A3A3A) else Color(0xFF444444), CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(when { muted -> "Muted"; env.live -> "Streaming"; else -> "Not connected" },
                    fontSize = (d.value * 0.083f).coerceAtLeast(11f).sp,
                    color = base?.copy(0.55f) ?: Color(0xFF6A6A6A), maxLines = 1)
            }
        }
    }
}

// ---- Clock -----------------------------------------------------------------------------------------

object DimClock {
    val DATE_PATTERNS = listOf("EEE, d MMM", "EEEE d MMMM", "d/M/yyyy", "MMM d")

    fun timePattern(h24: Boolean, seconds: Boolean) = (if (h24) "HH:mm" else "h:mm") + (if (seconds) ":ss" else "") + (if (h24) "" else " a")

    fun time(millis: Long, h24: Boolean, seconds: Boolean): String =
        DateTimeFormatter.ofPattern(timePattern(h24, seconds)).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())).uppercase()

    fun date(millis: Long, format: Int): String =
        DateTimeFormatter.ofPattern(DATE_PATTERNS[format.coerceIn(0, DATE_PATTERNS.size - 1)])
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

@Composable
private fun ClockWidget(w: DimWidget, modifier: Modifier) {
    val h24 = w.option("h24", false)
    val seconds = w.option("seconds", false)
    val showDate = w.option("date", true)
    // one recomposition a minute (a second only while seconds are shown), aligned to the clock edge
    val now by produceState(System.currentTimeMillis(), seconds) {
        while (true) {
            value = System.currentTimeMillis()
            val step = if (seconds) 1000L else 60_000L
            delay(step - value % step + 30)
        }
    }
    val time = DimClock.time(now, h24, seconds)
    val ink = w.ink()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val charW = if (w.fontStyle == DimFont.MONO) 0.62f else 0.54f
        val fit = minOf(maxHeight.value * (if (showDate) 0.62f else 0.84f), maxWidth.value / (time.length * charW))
        val size = (fit * 0.72f * w.fontScale).coerceAtMost(fit)
        with(LocalDensity.current) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(time, fontSize = size.dp.toSp(), fontFamily = dimFamily(w.fontStyle), fontWeight = w.fontWeight(),
                    color = ink, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                if (showDate) {
                    Text(DimClock.date(now, w.option("dateFmt", 0)), fontSize = (size * 0.3f).coerceAtLeast(12f).dp.toSp(),
                        fontFamily = dimFamily(w.fontStyle), color = ink.copy(0.6f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- Laptop stats ----------------------------------------------------------------------------------

private data class StatRow(val key: String, val label: String, val value: Int?, val unit: String)

object DimStats {
    val KEYS = listOf("cpu", "ram", "gpu", "vram", "temp")
    val LABELS = listOf("CPU", "RAM", "GPU", "VRAM", "Temperature")
    fun defaultOn(key: String) = key != "vram"
}

@Composable
private fun StatsWidget(w: DimWidget, env: DimEnv, modifier: Modifier) {
    if (!env.preview) {      // the laptop only samples while somebody watches
        DisposableEffect(Unit) {
            LaptopStats.watch(true)
            onDispose { LaptopStats.watch(false) }
        }
    }
    var tick by remember { mutableLongStateOf(0L) }
    if (!env.preview) LaunchedEffect(Unit) { while (true) { tick = SystemClock.elapsedRealtime(); delay(1000) } }
    @Suppress("UNUSED_EXPRESSION") tick
    val fresh = env.preview || LaptopStats.isFresh(SystemClock.elapsedRealtime())
    val never = !env.preview && LaptopStats.updatedAt == 0L
    val sample = mapOf("cpu" to 23, "ram" to 54, "gpu" to 12, "vram" to 31, "temp" to 48)
    fun read(k: String): Int? = if (!fresh) null else if (env.preview) sample[k] else when (k) {
        "cpu" -> LaptopStats.cpu; "ram" -> LaptopStats.ram; "gpu" -> LaptopStats.gpu
        "vram" -> LaptopStats.vram; else -> LaptopStats.temp
    }
    val rows = DimStats.KEYS.indices.filter { w.option(DimStats.KEYS[it], DimStats.defaultOn(DimStats.KEYS[it])) }
        .map { StatRow(DimStats.KEYS[it], DimStats.LABELS[it], read(DimStats.KEYS[it]), if (DimStats.KEYS[it] == "temp") "°C" else "%") }
    val bars = w.option("bars", false)
    val ink = w.ink()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val n = rows.size.coerceAtLeast(1)
        val fit = minOf(maxHeight.value / (n * (if (bars) 2.4f else 1.6f) + if (never) 1.2f else 0f), maxWidth.value / 9f)
        val fs = (fit * 0.8f * w.fontScale).coerceIn(10f, fit.coerceAtLeast(10f))
        with(LocalDensity.current) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                rows.forEach { r ->
                    val shown = r.value?.let { "$it${r.unit}" } ?: "—"
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, fontSize = fs.dp.toSp(), fontFamily = dimFamily(w.fontStyle), fontWeight = w.fontWeight(),
                                color = ink.copy(0.6f), maxLines = 1, modifier = Modifier.weight(1f))
                            Text(shown, fontSize = fs.dp.toSp(), fontFamily = dimFamily(w.fontStyle), fontWeight = w.fontWeight(),
                                color = ink, maxLines = 1)
                        }
                        if (bars) {
                            Spacer(Modifier.height((fs * 0.3f).dp))
                            Box(Modifier.fillMaxWidth().height((fs * 0.24f).coerceAtLeast(3f).dp).clip(CircleShape)
                                .background(ink.copy(0.15f))) {
                                val f = ((r.value ?: 0) / (if (r.unit == "°C") 100f else 100f)).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(ink, CircleShape))
                            }
                        }
                    }
                }
                if (never) {
                    Text("Laptop not connected", fontSize = (fs * 0.8f).coerceAtLeast(10f).dp.toSp(),
                        color = ink.copy(0.45f), maxLines = 1, modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ---- Camera ----------------------------------------------------------------------------------------

@Composable
private fun CameraWidget(w: DimWidget, env: DimEnv, modifier: Modifier) {
    val edge = w.color?.let { Color(it).copy(0.8f) } ?: Color(0xFF3A3A3A)
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).background(Color(0xFF0A0A0A)).border(1.5.dp, edge, shape), contentAlignment = Alignment.Center) {
        if (env.preview) {
            DeckIcon("camera", edge, 36.dp)
        } else if (!CameraUi.granted) {
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { CameraUi.granted = it }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Camera needs permission", fontSize = 13.sp, color = Color(0xFF8A8A8A), maxLines = 1)
                Box(Modifier.heightIn(min = 44.dp).clip(CircleShape).border(1.dp, Color(0xFF4A4A4A), CircleShape)
                    .clickable(role = Role.Button) { ask.launch(Manifest.permission.CAMERA) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) {
                    Text("Allow camera", fontSize = 14.sp, color = QuietText)
                }
            }
        } else {
            CameraPicture(Modifier.fillMaxSize())
        }
    }
}

/**
 * The live picture. The camera belongs to CameraKeeper, which opens it only while a preview is on screen, so the
 * camera runs while this widget is in Dim and stops when Dim closes.
 */
@Composable
private fun CameraPicture(modifier: Modifier) {
    val ctx = LocalContext.current
    val view = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(view) {
        val before = CameraUi.previewView         // the Camera tab may be under Dim: give its picture back afterwards
        CameraUi.previewView = view
        onDispose { if (CameraUi.previewView === view) CameraUi.previewView = before }
    }
    AndroidView({ view }, modifier)
}
