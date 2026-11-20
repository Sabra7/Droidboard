package dev.phonemic.ui.home.dim

import org.json.JSONArray
import org.json.JSONObject

/** What can sit on the Dim screen. */
enum class DimWidgetType(val key: String, val label: String, val icon: String, val minW: Int, val minH: Int) {
    MUTE("mute", "Mute button", "mic", 2, 2),
    CLOCK("clock", "Clock and date", "clock", 2, 1),
    STATS("stats", "Laptop stats", "cpu", 2, 2),
    CAMERA("camera", "Camera", "camera", 2, 2);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

object DimFont {
    const val DEFAULT = "default"
    const val MONO = "mono"
    const val LIGHT = "light"
    const val SERIF = "serif"
    val STYLES = listOf(DEFAULT, MONO, LIGHT, SERIF)
    val NAMES = listOf("Default", "Mono", "Light", "Serif")
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 1.4f
}

/**
 * One thing on the Dim screen, placed on a grid ([col], [row], [w], [h] in cells).
 * [color] null means the widget's own quiet default. [options] holds what only some widgets use
 * (the clock's 24-hour switch, the stats rows, ...), as text, so a new option never breaks an old layout.
 */
data class DimWidget(
    val id: String,
    val type: DimWidgetType,
    val col: Int, val row: Int, val w: Int, val h: Int,
    val color: Int? = null,
    val fontScale: Float = 1f,
    val fontStyle: String = DimFont.DEFAULT,
    val options: Map<String, String> = emptyMap(),
) {
    fun option(key: String, default: Boolean) = options[key]?.let { it == "1" } ?: default
    fun option(key: String, default: Int) = options[key]?.toIntOrNull() ?: default
    fun withOption(key: String, value: String) = copy(options = options + (key to value))
    fun withOption(key: String, value: Boolean) = withOption(key, if (value) "1" else "0")
    fun withOption(key: String, value: Int) = withOption(key, value.toString())
}

data class DimLayout(val widgets: List<DimWidget>)

/** The grid of one orientation: portrait is tall, landscape is wide. */
data class DimGrid(val cols: Int, val rows: Int) {
    companion object {
        val PORTRAIT = DimGrid(6, 12)
        val LANDSCAPE = DimGrid(12, 6)
        fun of(landscape: Boolean) = if (landscape) LANDSCAPE else PORTRAIT
    }
}

/** Grid maths and storage of the Dim layout. Pure, so it is tested without a phone. */
object DimConfig {
    /** What a fresh widget gets, by orientation. */
    fun defaultSize(type: DimWidgetType, landscape: Boolean): Pair<Int, Int> = if (landscape) when (type) {
        DimWidgetType.MUTE -> 3 to 3
        DimWidgetType.CLOCK -> 4 to 2
        DimWidgetType.STATS -> 3 to 3
        DimWidgetType.CAMERA -> 4 to 3
    } else when (type) {
        DimWidgetType.MUTE -> 4 to 4
        DimWidgetType.CLOCK -> 6 to 2
        DimWidgetType.STATS -> 4 to 3
        DimWidgetType.CAMERA -> 6 to 4
    }

    /** The S / M / L choice: the smallest allowed, the default, and a bigger one. */
    fun preset(type: DimWidgetType, landscape: Boolean, level: Int): Pair<Int, Int> {
        val grid = DimGrid.of(landscape)
        val (dw, dh) = defaultSize(type, landscape)
        return when (level) {
            0 -> type.minW.coerceAtLeast(dw / 2) to type.minH.coerceAtLeast(dh / 2)
            1 -> dw to dh
            else -> (dw + grid.cols / 6).coerceAtMost(grid.cols) to (dh + grid.rows / 6).coerceAtMost(grid.rows)
        }
    }

    /** Which of S / M / L the widget is (0..2), or -1 when it was resized by hand. */
    fun presetOf(widget: DimWidget, landscape: Boolean): Int =
        (0..2).firstOrNull { preset(widget.type, landscape, it) == (widget.w to widget.h) } ?: -1

    /** Today's Dim screen: one mute button in the middle. */
    fun defaultLayout(landscape: Boolean): DimLayout = DimLayout(listOf(
        if (landscape) DimWidget("mute", DimWidgetType.MUTE, 5, 2, 2, 2)
        else DimWidget("mute", DimWidgetType.MUTE, 1, 4, 4, 4)
    ))

    private fun overlap(a: DimWidget, b: DimWidget) =
        a.col < b.col + b.w && b.col < a.col + a.w && a.row < b.row + b.h && b.row < a.row + a.h

    /**
     * Puts [id] at the given place (kept inside the grid and never smaller than its minimum). Widgets in the way
     * are pushed down. Null when nothing fits, so the caller keeps the old layout.
     */
    fun place(layout: DimLayout, grid: DimGrid, id: String, col: Int, row: Int, w: Int, h: Int): DimLayout? {
        val moving = layout.widgets.firstOrNull { it.id == id } ?: return null
        val nw = w.coerceIn(moving.type.minW, grid.cols)
        val nh = h.coerceIn(moving.type.minH, grid.rows)
        val placed = moving.copy(col = col.coerceIn(0, grid.cols - nw), row = row.coerceIn(0, grid.rows - nh), w = nw, h = nh)
        val list = layout.widgets.map { if (it.id == id) placed else it }.toMutableList()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val mid = queue.removeFirst()
            val m = list.first { it.id == mid }
            for (i in list.indices) {
                val o = list[i]
                if (o.id == m.id || !overlap(m, o)) continue
                val pushed = o.copy(row = m.row + m.h)
                if (pushed.row + pushed.h > grid.rows) return null
                list[i] = pushed
                queue.addLast(o.id)
            }
        }
        return DimLayout(list)
    }

    fun move(layout: DimLayout, grid: DimGrid, id: String, col: Int, row: Int): DimLayout? {
        val w = layout.widgets.firstOrNull { it.id == id } ?: return null
        return place(layout, grid, id, col, row, w.w, w.h)
    }

    fun resize(layout: DimLayout, grid: DimGrid, id: String, w: Int, h: Int): DimLayout? {
        val m = layout.widgets.firstOrNull { it.id == id } ?: return null
        return place(layout, grid, id, m.col, m.row, w, h)
    }

    fun update(layout: DimLayout, widget: DimWidget) =
        DimLayout(layout.widgets.map { if (it.id == widget.id) widget else it })

    fun remove(layout: DimLayout, id: String) = DimLayout(layout.widgets.filter { it.id != id })

    /** A new widget in the first free spot (its default size, else the smallest). Null when the screen is full. */
    fun add(layout: DimLayout, grid: DimGrid, type: DimWidgetType, landscape: Boolean): DimLayout? {
        val (dw, dh) = defaultSize(type, landscape)
        val id = nextId(layout, type)
        for ((w, h) in listOf(dw.coerceAtMost(grid.cols) to dh.coerceAtMost(grid.rows), type.minW to type.minH)) {
            for (row in 0..grid.rows - h) for (col in 0..grid.cols - w) {
                val probe = DimWidget(id, type, col, row, w, h)
                if (layout.widgets.none { overlap(it, probe) }) return DimLayout(layout.widgets + probe)
            }
        }
        return null
    }

    fun nextId(layout: DimLayout, type: DimWidgetType): String {
        var n = 1
        while (layout.widgets.any { it.id == "${type.key}$n" }) n++
        return "${type.key}$n"
    }

    // ---- storage -----------------------------------------------------------------------------------

    fun encode(layout: DimLayout): String {
        val arr = JSONArray()
        layout.widgets.forEach { w ->
            arr.put(JSONObject().put("id", w.id).put("type", w.type.key)
                .put("col", w.col).put("row", w.row).put("w", w.w).put("h", w.h)
                .apply { w.color?.let { put("color", it) } }
                .put("fontScale", w.fontScale.toDouble()).put("fontStyle", w.fontStyle)
                .put("options", JSONObject(w.options)))
        }
        return JSONObject().put("v", 1).put("widgets", arr).toString()
    }

    /** The saved layout, or today's default when the text is empty, damaged or does not fit the grid. */
    fun decode(text: String?, landscape: Boolean): DimLayout {
        val fallback = defaultLayout(landscape)
        if (text.isNullOrBlank()) return fallback
        return try {
            val grid = DimGrid.of(landscape)
            val arr = JSONObject(text).getJSONArray("widgets")
            val list = ArrayList<DimWidget>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = DimWidgetType.fromKey(o.getString("type")) ?: return fallback
                val w = DimWidget(
                    id = o.getString("id"), type = type,
                    col = o.getInt("col"), row = o.getInt("row"), w = o.getInt("w"), h = o.getInt("h"),
                    color = if (o.has("color")) o.getInt("color") else null,
                    fontScale = o.optDouble("fontScale", 1.0).toFloat().coerceIn(DimFont.MIN_SCALE, DimFont.MAX_SCALE),
                    fontStyle = o.optString("fontStyle", DimFont.DEFAULT).takeIf { it in DimFont.STYLES } ?: DimFont.DEFAULT,
                    options = o.optJSONObject("options")?.let { opts ->
                        opts.keys().asSequence().associateWith { opts.getString(it) }
                    } ?: emptyMap(),
                )
                val fits = w.w >= type.minW && w.h >= type.minH && w.col >= 0 && w.row >= 0 &&
                    w.col + w.w <= grid.cols && w.row + w.h <= grid.rows
                if (!fits || list.any { it.id == w.id || overlap(it, w) }) return fallback
                list.add(w)
            }
            DimLayout(list)
        } catch (_: Exception) {
            fallback
        }
    }
}

/** Screen brightness of Dim: 1 % (today's) up to 100 %. The slider is bent so the dark end has room. */
object DimBrightness {
    const val MIN = 0.01f
    const val MAX = 1f
    fun fromSlider(p: Float): Float = MIN + p.coerceIn(0f, 1f).let { it * it } * (MAX - MIN)
    fun toSlider(b: Float): Float = kotlin.math.sqrt(((b.coerceIn(MIN, MAX) - MIN) / (MAX - MIN)).toDouble()).toFloat()
    fun percent(b: Float) = Math.round(b.coerceIn(MIN, MAX) * 100)
}
