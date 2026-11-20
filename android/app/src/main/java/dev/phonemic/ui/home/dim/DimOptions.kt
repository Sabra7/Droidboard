package dev.phonemic.ui.home.dim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.home.ChoicePills
import dev.phonemic.ui.home.SectionLabel
import dev.phonemic.ui.home.SettingSwitch
import dev.phonemic.ui.Hint

/** Everything you can change on one widget, in the editor's sheet. Every change is applied (and saved) at once. */
@Composable
fun DimWidgetOptions(
    widget: DimWidget,
    landscape: Boolean,
    onChange: (DimWidget) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onRemove: () -> Unit,
) {
    SectionLabel("Size")
    ChoicePills(listOf("Small", "Medium", "Large"), DimConfig.presetOf(widget, landscape)) { level ->
        val (c, r) = DimConfig.preset(widget.type, landscape, level)
        onResize(c, r)
    }
    Hint("Or pull the corner of the widget.")

    SectionLabel(if (widget.type == DimWidgetType.CAMERA) "Border colour" else "Colour")
    ColourPicker(widget.color) { onChange(widget.copy(color = it)) }

    when (widget.type) {
        DimWidgetType.CLOCK -> {
            SectionLabel("Clock")
            SettingSwitch("24-hour clock", "Off shows AM and PM.", widget.option("h24", false)) { onChange(widget.withOption("h24", it)) }
            SettingSwitch("Seconds", "The clock updates every second.", widget.option("seconds", false)) { onChange(widget.withOption("seconds", it)) }
            SettingSwitch("Date", "Under the time.", widget.option("date", true)) { onChange(widget.withOption("date", it)) }
            if (widget.option("date", true)) {
                val now = System.currentTimeMillis()
                val fmt = widget.option("dateFmt", 0).coerceIn(0, DimClock.DATE_PATTERNS.size - 1)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePills(listOf(0, 1).map { DimClock.date(now, it) }, if (fmt < 2) fmt else -1) { onChange(widget.withOption("dateFmt", it)) }
                    ChoicePills(listOf(2, 3).map { DimClock.date(now, it) }, if (fmt >= 2) fmt - 2 else -1) { onChange(widget.withOption("dateFmt", it + 2)) }
                }
            }
        }
        DimWidgetType.STATS -> {
            SectionLabel("Rows")
            DimStats.KEYS.indices.forEach { i ->
                val key = DimStats.KEYS[i]
                SettingSwitch(DimStats.LABELS[i],
                    if (key == "temp") "The laptop's GPU temperature." else "Of the laptop.",
                    widget.option(key, DimStats.defaultOn(key))) { onChange(widget.withOption(key, it)) }
            }
            SectionLabel("Look")
            ChoicePills(listOf("Text", "Bars"), if (widget.option("bars", false)) 1 else 0) { onChange(widget.withOption("bars", it == 1)) }
        }
        DimWidgetType.CAMERA -> Hint("Shows this device's camera, the one chosen in the Camera tab. It is only on while Dim is showing.")
        DimWidgetType.MUTE -> {}
    }

    if (widget.type != DimWidgetType.CAMERA) {
        SectionLabel("Font")
        if (widget.type != DimWidgetType.MUTE) {
            ChoicePills(DimFont.NAMES, DimFont.STYLES.indexOf(widget.fontStyle)) { onChange(widget.copy(fontStyle = DimFont.STYLES[it])) }
        }
        ChoicePills(listOf("Light", "Regular", "SemiBold"), widget.weightIndex()) { onChange(widget.withOption("weight", it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = widget.fontScale,
                onValueChange = { onChange(widget.copy(fontScale = it)) },
                valueRange = DimFont.MIN_SCALE..DimFont.MAX_SCALE,
                colors = quietSliderColors(),
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
        }
    }

    OutlinedButton(
        onClick = onRemove,
        border = BorderStroke(1.5.dp, StateColors.Red),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("Remove widget", fontSize = 16.sp, color = StateColors.Red) }
}

/** A slider whose empty part can be seen on a glass sheet. */
@Composable
fun quietSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(0.25f))

private val FixedColours = listOf(
    "White" to Color(0xFFF2F2F5), "Grey" to Color(0xFFA0A0A8), "Green" to StateColors.Live, "Amber" to StateColors.Wait,
    "Red" to StateColors.Red, "Blue" to Color(0xFF6AA9FF), "Pink" to Color(0xFFFF8FB1),
)

/** Swatches (Default, the Material You colours of the phone, a few fixed ones) and a custom colour from hue, saturation and brightness. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourPicker(current: Int?, onPick: (Int?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val you = listOf("Primary" to c.primary, "Secondary" to c.secondary, "Tertiary" to c.tertiary)
    val swatches = you + FixedColours
    var custom by remember { mutableStateOf(current != null && swatches.none { it.second.toArgb() == current }) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Swatch(null, "Default colour", current == null && !custom) { custom = false; onPick(null) }
        swatches.forEach { (name, colour) ->
            Swatch(colour, name, current == colour.toArgb() && !custom) { custom = false; onPick(colour.toArgb()) }
        }
        Swatch(null, "Custom colour", custom, custom = true) { custom = true; if (current == null) onPick(FixedColours[0].second.toArgb()) }
    }
    if (custom && current != null) {
        val hsv = remember { FloatArray(3) }
        android.graphics.Color.colorToHSV(current, hsv)
        val hue = hsv[0]; val sat = hsv[1]; val bri = hsv[2]
        fun pick(h: Float, s: Float, v: Float) = onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        Column {
            LabeledSlider("Hue", hue / 360f) { pick(it * 360f, sat.coerceAtLeast(0.05f), bri.coerceAtLeast(0.3f)) }
            LabeledSlider("Saturation", sat) { pick(hue, it, bri.coerceAtLeast(0.3f)) }
            LabeledSlider("Brightness", bri) { pick(hue, sat, it.coerceAtLeast(0.2f)) }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.75f), modifier = Modifier.size(width = 96.dp, height = 24.dp))
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, colors = quietSliderColors(), modifier = Modifier.weight(1f))
    }
}

/** A round colour, 48 dp to touch. The chosen one has a ring around it (no tick). null is Default (an empty ring) or Custom (a rainbow ring). */
@Composable
private fun Swatch(colour: Color?, name: String, selected: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (selected) 42.dp else 0.dp).border(2.dp, ink, CircleShape))
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .then(when {
                    colour != null -> Modifier.background(colour)
                    custom -> Modifier.background(androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    else -> Modifier.border(1.5.dp, ink.copy(0.5f), CircleShape)
                }),
            contentAlignment = Alignment.Center,
        ) {
            if (colour == null && !custom) DeckIcon("x", ink.copy(0.6f), 16.dp)
        }
    }
}
