package dev.phonemic.deck

import android.content.Context
import org.json.JSONObject

/** One group of icons in the picker (Media, Devices, ...). */
data class IconGroup(val name: String, val icons: List<String>)

/** The icon set: stroke-only SVG paths on a 24x24 grid, from shared/icons.json (Lucide, ISC license). */
class IconSet(val groups: List<IconGroup>, val paths: Map<String, List<String>>, val tags: Map<String, List<String>>) {
    val names: List<String> get() = paths.keys.toList()

    fun has(name: String) = paths.containsKey(name)

    /** Icons whose name or tags contain [query]; all of [pool] when the query is blank. */
    fun search(query: String, pool: List<String> = names): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return pool
        return pool.filter { n -> q in n || tags[n].orEmpty().any { q in it } }
    }

    companion object {
        fun parse(json: String): IconSet {
            val root = JSONObject(json)
            val icons = root.getJSONObject("icons")
            val paths = LinkedHashMap<String, List<String>>()
            val tags = HashMap<String, List<String>>()
            for (name in icons.keys()) {
                val entry = icons.getJSONObject(name)
                val pathData = entry.getJSONArray("d")
                paths[name] = List(pathData.length()) { pathData.getString(it) }
                val tagList = entry.optJSONArray("tags")
                tags[name] = if (tagList == null) emptyList() else List(tagList.length()) { tagList.getString(it) }
            }
            val groupArray = root.getJSONArray("groups")
            val groups = List(groupArray.length()) { i ->
                val group = groupArray.getJSONObject(i)
                val list = group.getJSONArray("icons")
                IconGroup(group.getString("name"), List(list.length()) { list.getString(it) }.filter { it in paths })
            }
            return IconSet(groups, paths, tags)
        }
    }
}

/** Loads the icon set once, from the app's assets. */
object DeckIconSet {
    @Volatile private var set: IconSet? = null

    fun get(ctx: Context): IconSet = set ?: synchronized(this) {
        set ?: IconSet.parse(ctx.assets.open("icons.json").bufferedReader().use { it.readText() }).also { set = it }
    }
}

/** Where a dragged button is over the grid. Pure, so it can be tested. */
object DeckLayout {
    /** Slot index (row-major) under the point (x, y), measured from the grid's top-left corner. */
    fun indexAt(x: Float, y: Float, cell: Float, gap: Float, cols: Int, count: Int): Int {
        val step = cell + gap
        val col = (x / step).toInt().coerceIn(0, cols - 1)
        val row = (y / step).toInt().coerceAtLeast(0)
        return (row * cols + col).coerceIn(0, (count - 1).coerceAtLeast(0))
    }
}
