package dev.phonemic.deck

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a deck button does on the laptop. [value] is the shortcut, the text, the target or the choice;
 * [extra] holds the command line arguments of an "open" button.
 */
data class DeckAction(val type: String, val value: String, val extra: String = "")

data class DeckButton(
    val id: String,
    val label: String,
    val icon: String,
    val color: String,
    val action: DeckAction,
)

data class Deck(val cols: Int, val buttons: List<DeckButton>)

/** The kinds of action the editor offers. [field] is the JSON key the laptop reads (windows/deck/layout.py). */
data class ActionKind(
    val type: String,
    val label: String,
    val field: String,
    val hint: String,
    val choices: List<Pair<String, String>> = emptyList(),
)

object DeckSpec {
    val COLORS = listOf("slate", "blue", "green", "amber", "red", "purple", "pink", "teal")
    const val MIN_COLS = 2
    const val MAX_COLS = 8
    const val MAX_BUTTONS = 48

    val KINDS = listOf(
        ActionKind("media", "Media", "key", "", listOf(
            "play_pause" to "Play / Pause", "next" to "Next track", "prev" to "Previous track",
            "stop" to "Stop", "vol_up" to "Volume up", "vol_down" to "Volume down",
            "vol_mute" to "Mute sound")),
        ActionKind("hotkey", "Shortcut", "keys", "Ctrl+Shift+M, F9, Win+Left"),
        ActionKind("text", "Type text", "text", "Text typed where the cursor is"),
        ActionKind("open", "Open", "target", "A website, file, folder or program path"),
        ActionKind("system", "System", "what", "", listOf(
            "lock" to "Lock the PC", "sleep" to "Sleep", "hibernate" to "Hibernate",
            "display_off" to "Turn the screen off", "sign_out" to "Sign out", "restart" to "Restart",
            "shutdown" to "Shut down", "task_manager" to "Task Manager", "settings" to "Windows Settings",
            "screenshot" to "Screenshot tool", "show_desktop" to "Show the desktop")),
        ActionKind("phonemic", "Droidboard", "what", "", listOf("mute" to "Mute / unmute the mic")),
    )

    fun kind(type: String) = KINDS.firstOrNull { it.type == type } ?: KINDS[0]
}

/** JSON the laptop speaks (windows/deck/server.py): one object per line. */
object DeckJson {
    fun parse(o: JSONObject): Deck {
        val arr = o.optJSONArray("buttons") ?: JSONArray()
        val buttons = (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val actionJson = item.optJSONObject("action") ?: return@mapNotNull null
            val type = actionJson.optString("type")
            val kind = DeckSpec.KINDS.firstOrNull { it.type == type } ?: return@mapNotNull null
            DeckButton(
                id = item.optString("id"), label = item.optString("label"), icon = item.optString("icon"),
                color = item.optString("color", "slate"),
                action = DeckAction(type, actionJson.optString(kind.field), actionJson.optString("args")),
            )
        }
        return Deck(o.optInt("cols", 4).coerceIn(DeckSpec.MIN_COLS, DeckSpec.MAX_COLS), buttons)
    }

    fun toJson(deck: Deck): JSONObject = JSONObject().apply {
        put("cols", deck.cols)
        put("buttons", JSONArray().apply {
            deck.buttons.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("label", b.label); put("icon", b.icon); put("color", b.color)
                    put("action", JSONObject().apply {
                        put("type", b.action.type)
                        put(DeckSpec.kind(b.action.type).field, b.action.value)
                        if (b.action.type == "open" && b.action.extra.isNotBlank()) put("args", b.action.extra)
                    })
                })
            }
        })
    }
}

/** Editing the layout: pure functions, each returns a new deck. */
object DeckEdit {
    fun newId(deck: Deck): String {
        var n = deck.buttons.size + 1
        while (deck.buttons.any { it.id == "b$n" }) n++
        return "b$n"
    }

    fun blank(deck: Deck) = DeckButton(newId(deck), "", "zap", "blue", DeckAction("media", "play_pause"))

    /** Replaces the button with the same id, or appends it when it is new. */
    fun put(deck: Deck, button: DeckButton): Deck {
        val i = deck.buttons.indexOfFirst { it.id == button.id }
        return deck.copy(
            buttons = if (i < 0) deck.buttons + button
            else deck.buttons.toMutableList().also { it[i] = button }
        )
    }

    fun remove(deck: Deck, id: String) = deck.copy(buttons = deck.buttons.filterNot { it.id == id })

    /** Moves a button [by] places (negative = earlier), stopping at the ends. */
    fun move(deck: Deck, id: String, by: Int): Deck {
        val from = deck.buttons.indexOfFirst { it.id == id }
        if (from < 0) return deck
        val to = (from + by).coerceIn(0, deck.buttons.size - 1)
        val list = deck.buttons.toMutableList()
        list.add(to, list.removeAt(from))
        return deck.copy(buttons = list)
    }

    fun canAdd(deck: Deck) = deck.buttons.size < DeckSpec.MAX_BUTTONS

    fun withCols(deck: Deck, cols: Int) = deck.copy(cols = cols.coerceIn(DeckSpec.MIN_COLS, DeckSpec.MAX_COLS))

    /** What the editor may save: the laptop refuses an action without a value. */
    fun isComplete(b: DeckButton) = b.action.value.isNotBlank() && (b.label.isNotBlank() || b.icon.isNotBlank())
}
