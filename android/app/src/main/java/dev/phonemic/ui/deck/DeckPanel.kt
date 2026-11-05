package dev.phonemic.ui.deck

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.phonemic.connection.LaptopFinder
import dev.phonemic.deck.Deck
import dev.phonemic.deck.DeckAction
import dev.phonemic.deck.DeckButton
import dev.phonemic.deck.DeckClient
import dev.phonemic.deck.DeckEdit
import dev.phonemic.deck.DeckIconSet
import dev.phonemic.deck.DeckLayout
import dev.phonemic.deck.DeckSpec
import dev.phonemic.deck.DeckState
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.LocalIsDark
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.home.LiveLamp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CellShape = RoundedCornerShape(12.dp)     // the card's 32 minus its 20 padding
private val CellGap = 12.dp

/** The button colours, toned down so they sit under the knobs and the live lamp instead of competing with them. */
private fun deckColor(name: String) = calm(when (name) {
    "blue" -> Color(0xFF4C8DFF)
    "green" -> Color(0xFF39D353)
    "amber" -> Color(0xFFFFB020)
    "red" -> Color(0xFFFF6B5E)
    "purple" -> Color(0xFFA77BFF)
    "pink" -> Color(0xFFFF7EB6)
    "teal" -> Color(0xFF2CD1C0)
    else -> Color(0xFF8A94A6)
})

/** About half the saturation and a little darker: each colour moves 45 % of the way to its own grey. */
private fun calm(c: Color): Color {
    val grey = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    fun ch(v: Float) = ((v + (grey - v) * 0.45f) * 0.92f).coerceIn(0f, 1f)
    return Color(ch(c.red), ch(c.green), ch(c.blue), c.alpha)
}

/** The same thin light edge the glass cards have, so the buttons read as part of the same surface. */
@Composable
private fun glassEdge(): Brush {
    val dark = LocalIsDark.current
    return Brush.linearGradient(
        if (dark) listOf(Color.White.copy(0.22f), Color.White.copy(0.04f))
        else listOf(Color.White, Color.Black.copy(0.08f))
    )
}

/** Stops a scroll that reached its end from carrying on into the scrolling area around it. */
private val KeepScrollInside = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/**
 * The button deck: glass buttons that run things on the laptop. "Edit" lets you change, add, drag and
 * delete buttons; the layout is saved on the laptop. [onSetup] opens Connection when there is no
 * address or pairing code yet.
 */
@Composable
fun DeckPanel(
    hazeState: HazeState, onSetup: () -> Unit, modifier: Modifier = Modifier,
    columns: Int? = null,      // a phone shows its own column count; the saved one (from the laptop) stays for the tablet
) {
    var deck by remember { mutableStateOf(DeckState.deck) }
    var status by remember { mutableStateOf(DeckState.status) }
    var online by remember { mutableStateOf(DeckState.online) }
    var failure by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DeckButton?>(null) }      // the button open in the editor
    var isNew by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        LaptopFinder.start(ctx)          // follows the laptop when the Wi-Fi (and its address) changes
        DeckClient.start()
        onDispose { DeckClient.stop(); LaptopFinder.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            // the screen shows the laptop's version unless the editor is open (it would jump under the finger)
            if (selected == null) deck = DeckState.deck
            status = DeckState.status; online = DeckState.online
            failure = if (SystemClock.elapsedRealtime() - DeckState.resultAt < 4000) DeckState.result else ""
            delay(200)
        }
    }

    val ink = MaterialTheme.colorScheme.onSurface
    GlassCard(hazeState, modifier) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Deck", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.size(12.dp))
                if (online) LiveLamp("LIVE")
                else Text("Offline", fontSize = 13.sp, color = ink.copy(0.75f))
                Spacer(Modifier.weight(1f))
                if (deck != null && selected == null) {
                    TextButton(onClick = { editing = !editing }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (editing) "Done" else "Edit", fontSize = 16.sp)
                    }
                }
            }

            val d = deck
            when {
                selected != null && d != null -> ButtonEditor(
                    button = selected!!, isNew = isNew,
                    onSave = { updated -> DeckClient.save(DeckEdit.put(d, updated)); selected = null },
                    onDelete = { DeckClient.save(DeckEdit.remove(d, selected!!.id)); selected = null },
                    onCancel = { selected = null },
                    modifier = Modifier.weight(1f),
                )
                d == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(status, fontSize = 15.sp, color = ink.copy(0.85f))
                    OutlinedButton(onClick = onSetup, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Connection", color = ink)
                    }
                }
                else -> {
                    if (editing) {
                        Text("Hold a button, then drag it to move it. Tap to change it.", fontSize = 13.sp,
                            color = ink.copy(0.75f))
                        if (columns == null) ColumnsStepper(d.cols) { DeckEdit.withCols(d, it).let { nd -> DeckClient.save(nd); deck = nd } }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        DeckGrid(d, columns ?: d.cols, editing,
                            onPress = { DeckClient.press(it.id) },
                            onEdit = { selected = it; isNew = false },
                            onAdd = { selected = DeckEdit.blank(d); isNew = true },
                            onReorder = { nd -> DeckClient.save(nd); deck = nd })
                    }
                    if (failure.isNotEmpty()) Text(failure, fontSize = 14.sp, color = StateColors.Red)
                    // "Offline" in the header already says it; only a line that asks for something is shown
                    else if (!online && !status.startsWith("Can't reach") && !status.startsWith("Connecting")) Text(status, fontSize = 13.sp, color = ink.copy(0.7f))
                }
            }
        }
    }
}

@Composable
private fun DeckGrid(deck: Deck, cols: Int, editing: Boolean, onPress: (DeckButton) -> Unit,
                     onEdit: (DeckButton) -> Unit, onAdd: () -> Unit, onReorder: (Deck) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellDp = (maxWidth - CellGap * (cols - 1)) / cols
        val cell = with(density) { cellDp.toPx() }
        val gap = with(density) { CellGap.toPx() }
        val step = cell + gap

        // While a button is dragged the order changes live; it is sent to the laptop when the finger lifts.
        var order by remember(deck) { mutableStateOf(deck.buttons) }
        var dragId by remember { mutableStateOf<String?>(null) }
        var dragCenter by remember { mutableStateOf(Offset.Zero) }

        val showAdd = editing && DeckEdit.canAdd(deck)
        val slots = order.size + if (showAdd) 1 else 0
        val rows = ((slots + cols - 1) / cols).coerceAtLeast(1)
        fun slotCenter(i: Int) = Offset((i % cols) * step + cell / 2, (i / cols) * step + cell / 2)

        Box(Modifier.fillMaxWidth().height(cellDp * rows + CellGap * (rows - 1))) {
            order.forEachIndexed { i, b ->
                key(b.id) {
                    val dragging = dragId == b.id
                    val glide = if (dragId != null && !dragging) spring<Float>() else snap()   // the others slide aside
                    val x by animateFloatAsState((i % cols) * step, glide, label = "x")
                    val y by animateFloatAsState((i / cols) * step, glide, label = "y")
                    val pos = if (dragging) dragCenter - Offset(cell / 2, cell / 2) else Offset(x, y)
                    DeckCell(
                        b, cellDp, editing,
                        modifier = Modifier
                            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { if (dragging) { scaleX = 1.06f; scaleY = 1.06f; alpha = 0.94f } }
                            .pointerInput(editing, cols, cell) {
                                if (!editing) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = b.id
                                        dragCenter = slotCenter(order.indexOfFirst { it.id == b.id })
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragCenter += amount
                                        val from = order.indexOfFirst { it.id == b.id }
                                        val to = DeckLayout.indexAt(dragCenter.x, dragCenter.y, cell, gap, cols, order.size)
                                        if (to != from) order = DeckEdit.move(Deck(cols, order), b.id, to - from).buttons
                                    },
                                    onDragEnd = {
                                        dragId = null
                                        if (order != deck.buttons) onReorder(Deck(deck.cols, order))   // keep the saved column count
                                    },
                                    onDragCancel = { dragId = null; order = deck.buttons },
                                )
                            },
                        onClick = { if (editing) onEdit(b) else onPress(b) },
                    )
                }
            }
            if (showAdd) {
                val i = order.size
                AddCell(cellDp, onAdd,
                    Modifier.offset { IntOffset(((i % cols) * step).roundToInt(), ((i / cols) * step).roundToInt()) })
            }
        }
    }
}

@Composable
private fun DeckCell(b: DeckButton, size: Dp, editing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = deckColor(b.color)
    val ink = MaterialTheme.colorScheme.onSurface
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) { if (flash) { delay(140); flash = false } }
    val edge = glassEdge()
    Box(
        modifier.size(size).clip(CellShape)
            .background(ink.copy(if (flash) 0.16f else 0.06f))
            .let { if (editing) it.border(1.5.dp, accent.copy(0.7f), CellShape) else it.border(1.dp, edge, CellShape) }
            .clickable(role = Role.Button, onClickLabel = b.label) { flash = true; onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // tight enough that a two-line label still fits under the icon on a phone
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (b.icon.isNotEmpty()) DeckIcon(b.icon, accent, 32.dp)
            if (b.label.isNotEmpty()) Text(b.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, color = ink.copy(0.92f))
        }
    }
}

@Composable
private fun AddCell(size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(size).clip(CellShape).border(1.dp, ink.copy(0.22f), CellShape)
            .clickable(role = Role.Button, onClickLabel = "Add a button", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DeckIcon("plus", ink.copy(0.65f), 28.dp) }
}

@Composable
private fun ColumnsStepper(cols: Int, onChange: (Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Columns", fontSize = 14.sp, color = ink.copy(0.8f), modifier = Modifier.width(90.dp))
        OutlinedButton(onClick = { onChange(cols - 1) }, enabled = cols > DeckSpec.MIN_COLS,
            modifier = Modifier.heightIn(min = 44.dp)) { DeckIcon("minus", ink, 18.dp) }
        Text("$cols", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = ink, modifier = Modifier.width(48.dp))
        OutlinedButton(onClick = { onChange(cols + 1) }, enabled = cols < DeckSpec.MAX_COLS,
            modifier = Modifier.heightIn(min = 44.dp)) { DeckIcon("plus", ink, 18.dp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonEditor(
    button: DeckButton, isNew: Boolean,
    onSave: (DeckButton) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(button) }
    val kind = DeckSpec.kind(draft.action.type)
    val ink = MaterialTheme.colorScheme.onSurface

    // the fields scroll; Save / Cancel stay pinned at the bottom
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(ink.copy(0.06f))
                    .border(1.dp, glassEdge(), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    DeckIcon(draft.icon, deckColor(draft.color), 30.dp)
                }
                OutlinedTextField(
                    value = draft.label, singleLine = true, label = { Text("Name") },
                    onValueChange = { draft = draft.copy(label = it.take(24)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Section("Icon")
            IconPicker(draft.icon) { draft = draft.copy(icon = it) }
            Section("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeckSpec.COLORS.forEach { name ->
                    val on = name == draft.color
                    Box(Modifier.size(36.dp).clip(CircleShape).background(deckColor(name))
                        .border(if (on) 3.dp else 0.dp, ink, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = name) { draft = draft.copy(color = name) })
                }
            }
            Section("What it does")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckSpec.KINDS.forEach { k ->
                    Chip(k.label, k.type == draft.action.type) {
                        val first = k.choices.firstOrNull()?.first ?: ""
                        draft = draft.copy(action = DeckAction(k.type, if (k.type == draft.action.type) draft.action.value else first))
                    }
                }
            }
            if (kind.choices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kind.choices.forEach { (value, label) ->
                        Chip(label, value == draft.action.value) { draft = draft.copy(action = DeckAction(kind.type, value)) }
                    }
                }
            } else {
                OutlinedTextField(
                    value = draft.action.value, label = { Text(kind.hint) },
                    onValueChange = { draft = draft.copy(action = draft.action.copy(value = it.take(500))) },
                    singleLine = kind.type != "text", modifier = Modifier.fillMaxWidth(),
                )
                if (kind.type == "open") {
                    OutlinedTextField(
                        value = draft.action.extra, label = { Text("Arguments (optional)") }, singleLine = true,
                        onValueChange = { draft = draft.copy(action = draft.action.copy(extra = it.take(300))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Easier on the laptop: the Droidboard tray menu has an editor with a list of installed apps.",
                        fontSize = 13.sp, color = ink.copy(0.7f))
                }
            }
            if (draft.action != button.action) {
                Text("A new or changed action has to be approved on the laptop.", fontSize = 13.sp, color = ink.copy(0.7f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(draft) }, enabled = DeckEdit.isComplete(draft),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = ink) }
            Spacer(Modifier.weight(1f))
            if (!isNew) TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Delete", color = StateColors.Red)
            }
        }
    }
}

@Composable
internal fun Section(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(0.18f) else ink.copy(0.06f))
            .border(1.dp, ink.copy(if (selected) 0.45f else 0.14f), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = ink) }
}

/** Searchable icon grid. Its own scroll stays inside it: at the end it does not pull the editor along. */
@Composable
private fun IconPicker(current: String, onPick: (String) -> Unit) {
    val set = DeckIconSet.get(LocalContext.current)
    var group by remember { mutableIntStateOf(0) }          // 0 = all icons
    var query by remember { mutableStateOf("") }
    val ink = MaterialTheme.colorScheme.onSurface
    val pool = if (group == 0) set.names else set.groups[group - 1].icons
    val shown = set.search(query, pool)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search icons") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", group == 0) { group = 0 }
            set.groups.forEachIndexed { i, g -> Chip(g.name, group == i + 1) { group = i + 1 } }
        }
        Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(18.dp)).background(ink.copy(0.05f))
            .nestedScroll(KeepScrollInside)) {
            if (shown.isEmpty()) {
                Text("No icon matches", fontSize = 14.sp, color = ink.copy(0.7f), modifier = Modifier.padding(16.dp))
            } else {
                LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.fillMaxWidth().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it }) { name ->
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (name == current) MaterialTheme.colorScheme.primary.copy(0.22f) else Color.Transparent)
                            .clickable(role = Role.Button, onClickLabel = name) { onPick(name) },
                            contentAlignment = Alignment.Center) { DeckIcon(name, ink, 26.dp) }
                    }
                }
            }
        }
    }
}
