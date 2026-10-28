package app.mise.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Aisle
import app.mise.data.ListItem
import app.mise.data.PlanRepo
import app.mise.data.Prefs
import app.mise.sync.Supa
import app.mise.sync.SyncEngine
import app.mise.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The initial shown next to a tick ("J"), when the list is shared. */
fun myInitial(): String = if (SyncEngine.active()) Supa.displayName.trim().take(1).uppercase() else ""

/** LIVE when shared and in step; OFFLINE or a tap-to-retry problem otherwise. */
@Composable
fun SyncPill() {
    val s by SyncEngine.status.collectAsState()
    if (!SyncEngine.active()) return
    when (val v = s) {
        is SyncStatus.Synced -> Pill("Live", dot = true)
        SyncStatus.Working, SyncStatus.Off -> Pill("Syncing")
        SyncStatus.Offline -> Pill("Offline") { Toasts.show("No connection. Changes are kept and sent when you are back online.") }
        is SyncStatus.Problem -> Pill("Retry sync") {
            SyncEngine.poke()
            Toasts.show(v.message)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScreen(weekStart: LocalDate, onWeek: (LocalDate) -> Unit, onSettings: () -> Unit, onAddItem: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    var untilText by rememberSaveable(weekStart) { mutableStateOf("") }
    val until = untilText.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    val data by remember(weekStart, untilText) { repo.list(weekStart, until) }.collectAsState(initial = null)
    var lingering by remember { mutableStateOf(setOf<String>()) }
    var basketOpen by rememberSaveable { mutableStateOf(false) }
    var staplesOpen by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<ListItem?>(null) }
    val scope = rememberCoroutineScope()
    val tick = Haptic.rememberTick()
    val longPress = Haptic.rememberLongPress()

    fun toggle(item: ListItem) {
        val now = !item.checked
        tick(now)
        val by = myInitial()
        repo.scope.launch { repo.setChecked(item, now, by) }
        if (now) {
            lingering = lingering + item.id
            scope.launch {
                delay(1400)
                lingering = lingering - item.id
            }
            Toasts.undo("${item.name} in the basket") { repo.scope.launch { repo.setChecked(item.copy(checked = true), false, by) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val d = data
        val dishes = d?.dishes ?: 0
        ScreenHeader(
            eyebrow = weekTitle(weekStart) + " · " + if (dishes == 1) "1 dish" else "$dishes dishes",
            title = "List",
            trailing = { SyncPill() },
            actionIcon = MiseIcons.Create, actionLabel = "Add an item", onAction = onAddItem,
        )
        WeekNav(weekStart, onWeek)
        if (d == null) return@Column

        val staples = d.items.filter { it.staple && it.extraId == null }
        val main = d.items.filter { !(it.staple && it.extraId == null) }
        val pending = main.filter { !it.checked || it.id in lingering }
        val basket = main.filter { it.checked && it.id !in lingering }
        val toBuy = main.count { !it.checked }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
            item(key = "summary") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CappedText(1.3f) {
                        Text(toBuy.toString(), style = Mise.type.number, color = c.text)
                    }
                    Text(" to buy", style = Mise.type.bodyS, color = c.text2)
                    if (basket.isNotEmpty()) Text(" · ${basket.size} in the basket", style = Mise.type.bodyS, color = c.text2)
                    Spacer(Modifier.weight(1f))
                    ShopUntil(weekStart, until) { untilText = it?.toString() ?: "" }
                }
                val total = main.size
                Box(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth().height(3.dp).background(c.line, RoundedCornerShape(2.dp))) {
                    val done = if (total == 0) 0f else (total - toBuy).toFloat() / total
                    Box(Modifier.fillMaxWidth(done).height(3.dp).background(c.disc, RoundedCornerShape(2.dp)))
                }
            }
            if (!Prefs.listHintSeen && pending.isNotEmpty()) {
                item(key = "hint") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).background(c.soft, CardShape).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Tap to tick. Press and hold for options: aisle, staple, same as.", style = Mise.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
                        TextAction("Got it", { Prefs.markListHintSeen() })
                    }
                }
            }
            if (main.isEmpty() && staples.isEmpty() && d.lastWeek.isEmpty()) {
                item(key = "empty") {
                    Spacer(Modifier.height(12.dp))
                    EmptyState(
                        if (dishes == 0) "Nothing planned for this week" else "No ingredients yet",
                        if (dishes == 0) "Plan dishes and the list builds itself, merged and sorted by aisle. You can add anything else with +." else "The planned recipes have no ingredients yet. Add them in Recipes.",
                    )
                }
            }
            val grouped = pending.groupBy { it.aisle }
            Aisle.entries.forEach { aisle ->
                val rows = grouped[aisle].orEmpty()
                if (rows.isEmpty()) return@forEach
                item(key = "aisle:${aisle.key}") { SectionLabel(aisle.label, rows.count { !it.checked }.toString(), Modifier.animateItem()) }
                itemsIndexed(rows, key = { _, it -> it.id }) { i, item ->
                    ListRow(
                        item, first = i == 0, last = i == rows.lastIndex,
                        modifier = Modifier.animateItem(),
                        onToggle = { toggle(item) },
                        onOptions = { longPress(); options = item },
                    )
                }
            }
            item(key = "add") {
                AddRow("Add an item", Modifier.padding(top = 8.dp).animateItem(), muted = true, onClick = onAddItem)
            }
            if (d.lastWeek.isNotEmpty()) {
                item(key = "lastLabel") { SectionLabel("From last week, not bought", d.lastWeek.size.toString(), Modifier.animateItem()) }
                itemsIndexed(d.lastWeek, key = { _, it -> it.id }) { i, item ->
                    ListRow(item, i == 0, i == d.lastWeek.lastIndex, Modifier.animateItem(), onToggle = { toggle(item) }, onOptions = { longPress(); options = item })
                }
            }
            collapsible(
                key = "staples", label = "Usually at home", count = staples.size, open = staplesOpen, onOpen = { staplesOpen = !staplesOpen },
                rows = staples, onToggle = ::toggle, onOptions = { longPress(); options = it },
                note = "Staples are left off the list. Tick one you are out of, or hold it to change.",
            )
            collapsible(
                key = "basket", label = "In the basket", count = basket.size, open = basketOpen, onOpen = { basketOpen = !basketOpen },
                rows = basket, onToggle = ::toggle, onOptions = { longPress(); options = it },
            )
        }
    }

    options?.let { item ->
        ItemOptionsSheet(item, data?.items.orEmpty(), onDismiss = { options = null }, onToggle = { toggle(item); options = null })
    }
}

private fun LazyListScope.collapsible(
    key: String,
    label: String,
    count: Int,
    open: Boolean,
    onOpen: () -> Unit,
    rows: List<ListItem>,
    onToggle: (ListItem) -> Unit,
    onOptions: (ListItem) -> Unit,
    note: String? = null,
) {
    if (count == 0) return
    item(key = "$key:head") {
        val c = Mise.colours
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show", onClick = onOpen).padding(horizontal = 4.dp).animateItem(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(), style = Mise.type.label, color = c.text2, modifier = Modifier.weight(1f))
            Text(count.toString(), style = Mise.type.label, color = c.text2)
            Spacer(Modifier.width(8.dp))
            Icon(MiseIcons.Forward, contentDescription = null, tint = c.text2, modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f))
        }
    }
    if (!open) return
    if (note != null) item(key = "$key:note") {
        Text(note, style = Mise.type.bodyS, color = Mise.colours.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).animateItem())
    }
    itemsIndexed(rows, key = { _, it -> "$key:" + it.id }) { i, item ->
        ListRow(item, i == 0, i == rows.lastIndex, Modifier.animateItem(), onToggle = { onToggle(item) }, onOptions = { onOptions(item) })
    }
}

/** "Shop for: whole week" or through a chosen day, for a mid-week shop. */
@Composable
private fun ShopUntil(weekStart: LocalDate, until: LocalDate?, onPick: (LocalDate?) -> Unit) {
    val c = Mise.colours
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(if (until == null) "Whole week" else "Until " + dayName(until, full = false)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            DropdownMenuItem(text = { Text("Whole week", style = Mise.type.body, color = c.text) }, onClick = { onPick(null); open = false })
            (0..5).map { weekStart.plusDays(it.toLong()) }.forEach { d ->
                DropdownMenuItem(text = { Text("Through " + dayName(d), style = Mise.type.body, color = c.text) }, onClick = { onPick(d); open = false })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(item: ListItem, first: Boolean, last: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit, onOptions: () -> Unit) {
    val c = Mise.colours
    val ring by animateColorAsState(if (item.checked) c.control else c.text2, label = "ring")
    Column(modifier.fillMaxWidth().joinedCell(first, last, c.surface, c.line)) {
        if (!first) Divider(Modifier.padding(start = 56.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .combinedClickable(role = Role.Checkbox, onClickLabel = if (item.checked) "Untick" else "Tick", onLongClickLabel = "Options", onClick = onToggle, onLongClick = onOptions)
                .semantics(mergeDescendants = true) { stateDescription = if (item.checked) "Ticked" else "Not ticked" }
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).border(BorderStroke(1.5.dp, ring), CircleShape)
                    .then(if (item.checked) Modifier.background(c.control, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (item.checked) {
                    if (item.checkedBy.isNotBlank()) Text(item.checkedBy.take(1), style = Mise.type.labelS, color = c.onControl)
                    else Icon(MiseIcons.Check, contentDescription = null, tint = c.onControl, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, style = Mise.type.bodyM, color = if (item.checked) c.text2 else c.text,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    item.more?.let { add(it) }
                    if (item.extraId != null) add("Added")
                    if (item.carried) add("From last week")
                    if (item.dishes.isNotEmpty()) add(if (item.dishes.size <= 2) item.dishes.joinToString(", ") else item.dishes.take(2).joinToString(", ") + " +${item.dishes.size - 2}")
                    if (item.checked && item.checkedBy.isNotBlank()) add("by ${item.checkedBy}")
                }
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 150.dp)) {
                if (item.amount.isNotBlank()) Text(item.amount, style = Mise.type.number, color = if (item.checked) c.text2 else c.text, textAlign = TextAlign.End, maxLines = 2)
                item.detail?.let { Text(it, style = Mise.type.bodyS, color = c.text2, textAlign = TextAlign.End, maxLines = 1) }
            }
        }
    }
}

/**
 * The same options for every item: aisle, staple and "Same as" for recipe lines; edit and
 * remove for added items. Long press never deletes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemOptionsSheet(item: ListItem, all: List<ListItem>, onDismiss: () -> Unit, onToggle: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(item.amount) }
    var sameAs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Prefs.markListHintSeen() }
    MiseSheet(title = item.name, eyebrow = listOfNotNull(item.amount.ifBlank { null }, item.aisle.label).joinToString(" · "), onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
            if (item.dishes.isNotEmpty()) {
                Text("For " + item.dishes.joinToString(", "), style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
            GhostButton(if (item.checked) "Untick" else "Tick, it is in the basket", onToggle, Modifier.fillMaxWidth())
            if (item.extraId != null) {
                SectionLabel("Item")
                MiseField(name, { name = it }, "Item", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MiseField(qty, { qty = it.take(24) }, "How much (optional)", Modifier.fillMaxWidth())
                if (name.trim() != item.name || qty.trim() != item.amount) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Save", {
                        val id = item.extraId; val n = name; val q = qty
                        if (n.isNotBlank()) repo.scope.launch { repo.updateExtra(id, n, q, item.aisle) }
                        onDismiss()
                    }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
                }
            }
            SectionLabel("Aisle", "Mise remembers")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Aisle.entries.forEach { a ->
                    MiseChip(a.label, a == item.aisle) {
                        repo.scope.launch {
                            if (item.extraId != null) repo.updateExtra(item.extraId, name.ifBlank { item.name }, qty, a)
                            else repo.setAisle(item.key, item.name, a)
                        }
                        Toasts.show("${item.name} moves to ${a.label}")
                        onDismiss()
                    }
                }
            }
            if (item.extraId == null) {
                Spacer(Modifier.height(8.dp))
                MiseSwitch(
                    item.staple,
                    { on ->
                        repo.scope.launch { repo.setStaple(item.key, item.name, on) }
                        Toasts.show(if (on) "${item.name} is a staple: kept under Usually at home" else "${item.name} goes on the list from now on")
                        onDismiss()
                    },
                    "Usually at home",
                    sub = "Staples stay off the list until you need them",
                    inset = 4.dp,
                )
                val others = all.filter { it.extraId == null && it.key != item.key }.distinctBy { it.key }.sortedBy { it.name.lowercase() }
                if (others.isNotEmpty()) {
                    AddRow(if (sameAs) "Same as… (choose the item it matches)" else "Same as…", onClick = { sameAs = !sameAs })
                    if (sameAs) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            others.forEach { o ->
                                MiseChip(o.name, false) {
                                    repo.scope.launch { repo.setSameAs(item.key, item.name, o.key) }
                                    Toasts.show("${item.name} now joins ${o.name} on the list")
                                    onDismiss()
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                GhostButton("Remove from the list", {
                    val id = item.extraId
                    repo.scope.launch {
                        val gone = repo.removeExtra(id)
                        if (gone != null) Toasts.undo("Removed ${gone.name}") { repo.scope.launch { repo.restoreExtra(gone) } }
                    }
                    onDismiss()
                }, Modifier.fillMaxWidth(), danger = true)
            }
        }
    }
}
