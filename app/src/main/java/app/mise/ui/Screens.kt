package app.mise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Aisle
import app.mise.data.Names
import app.mise.data.Pantry
import app.mise.data.PantryItem
import app.mise.data.PlanRepo
import kotlinx.coroutines.launch

/**
 * Pantry: what you keep at home and how much is left. Have stays off the list (under Usually
 * at home). Low and Out go on the list, even when no dish needs them, and come back to Have
 * when ticked. Press and hold an item to stop keeping it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PantryScreen(onSettings: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val items by remember { repo.pantry() }.collectAsState(initial = null)
    var add by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val tick = Haptic.rememberTick()
    val longPress = Haptic.rememberLongPress()

    fun addItem() {
        val n = add.trim().replaceFirstChar { it.uppercase() }
        if (n.isEmpty()) return
        repo.scope.launch { repo.setLevel(Names.keyOf(n), n, Pantry.HAVE) }
        Toasts.show("$n is in the Pantry")
        add = ""
    }

    fun set(i: PantryItem, level: Int) {
        if (level == i.level) return
        tick(level == Pantry.HAVE)
        repo.scope.launch { repo.setLevel(i.key, i.name, level) }
        when (level) {
            Pantry.LOW, Pantry.OUT -> Toasts.undo("${i.name} goes on the list") { repo.scope.launch { repo.setLevel(i.key, i.name, i.level) } }
            Pantry.NONE -> Toasts.undo("${i.name} is no longer kept") { repo.scope.launch { repo.setLevel(i.key, i.name, i.level) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val all = items
        val low = all.orEmpty().count { it.level == Pantry.LOW }
        val out = all.orEmpty().count { it.level == Pantry.OUT }
        ScreenHeader(
            eyebrow = when {
                all == null -> "At home"
                low + out == 0 -> "${all.size} items · all in stock"
                else -> listOfNotNull(low.takeIf { it > 0 }?.let { "$it low" }, out.takeIf { it > 0 }?.let { "$it out" }).joinToString(" · ")
            },
            title = "Pantry",
            actionIcon = MiseIcons.Settings, actionLabel = "Settings", onAction = onSettings,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item(key = "add") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiseField(add, { add = it }, "Add something you keep, e.g. Jaggery", Modifier.weight(1f), imeAction = ImeAction.Done, onIme = { addItem() })
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("Add", { addItem() }, enabled = add.isNotBlank())
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiseChip("All", filter == 0, count = all?.size) { filter = 0 }
                    MiseChip("Low", filter == Pantry.LOW, count = low) { filter = if (filter == Pantry.LOW) 0 else Pantry.LOW }
                    MiseChip("Out", filter == Pantry.OUT, count = out) { filter = if (filter == Pantry.OUT) 0 else Pantry.OUT }
                }
            }
            val shown = all.orEmpty().filter { filter == 0 || it.level == filter }
            val grouped = shown.groupBy { it.aisle }
            Aisle.entries.forEach { aisle ->
                val rows = grouped[aisle].orEmpty()
                if (rows.isEmpty()) return@forEach
                item(key = "a:" + aisle.key) { SectionLabel(aisle.label, rows.size.toString(), Modifier.animateItem()) }
                itemsIndexed(rows, key = { _, it -> it.key }) { i, p ->
                    Column(Modifier.fillMaxWidth().animateItem().joinedCell(i == 0, i == rows.lastIndex, c.surface, c.line)) {
                        if (i > 0) Divider(Modifier.padding(start = 16.dp))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .combinedClickable(onClick = {}, onLongClickLabel = "Stop keeping", onLongClick = { longPress(); set(p, Pantry.NONE) })
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = Mise.type.bodyM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (p.level) { Pantry.LOW -> "Running low · on the list"; Pantry.OUT -> "Out · on the list"; else -> "In stock" },
                                    style = Mise.type.bodyS, color = c.text2,
                                )
                            }
                            LevelPicker(p.level) { set(p, it) }
                        }
                    }
                }
            }
            if (all != null && shown.isEmpty()) item(key = "empty") {
                Spacer(Modifier.height(12.dp))
                EmptyState(
                    if (filter == 0) "Nothing kept yet" else "Nothing here",
                    if (filter == 0) "Add what you always keep at home. Everyday spices and oils from your recipes appear here by themselves." else "Everything in this view is in stock.",
                )
            }
        }
    }
}

/** Have, Low, Out as one compact segmented control. */
@Composable
private fun LevelPicker(level: Int, onPick: (Int) -> Unit) {
    val c = Mise.colours
    CappedText(1.2f) {
        Row(Modifier.border(BorderStroke(1.dp, c.line), PillShape).padding(2.dp)) {
            listOf(Pantry.HAVE to "Have", Pantry.LOW to "Low", Pantry.OUT to "Out").forEach { (lv, label) ->
                val on = lv == level
                Box(
                    Modifier.heightIn(min = 40.dp)
                        .then(if (on) Modifier.background(c.control, PillShape) else Modifier)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(lv) })
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label.uppercase(), style = Mise.type.labelS, color = if (on) c.onControl else c.text)
                }
            }
        }
    }
}
