package app.mise.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Aisle
import app.mise.data.GroceryLine
import app.mise.data.PlanRepo
import app.mise.data.WeekList
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ListScreen(weekStart: LocalDate, onWeek: (LocalDate) -> Unit, onSettings: () -> Unit, onAddItem: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val data by remember(weekStart) { repo.list(weekStart) }.collectAsState(initial = null)
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showStaples by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<GroceryLine?>(null) }

    fun toggle(key: String, checked: Boolean) { repo.scope.launch { repo.setChecked(weekStart, key, checked) } }

    Column(Modifier.fillMaxSize()) {
        val d = data
        val dishes = d?.dishes ?: 0
        ScreenHeader(
            eyebrow = if (dishes == 1) "1 dish planned" else "$dishes dishes planned",
            title = "Grocery list",
            actionIcon = MiseIcons.Create, actionLabel = "Add an item", onAction = onAddItem,
        )
        WeekNav(weekStart, onWeek)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            if (d == null) return@Column
            val main = d.lines.filter { it.key !in d.staples }
            val staples = d.lines.filter { it.key in d.staples }
            val total = main.size + d.extras.size
            val left = main.count { "ing:${it.key}" !in d.checked } + d.extras.count { !it.checked }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(left.toString(), style = Mise.type.number, color = c.text)
                        Text(if (left == 1) " item left" else " items left", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
                        if (total > 0) Text("$total TOTAL", style = Mise.type.labelS, color = c.text2)
                    }
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).background(c.line, RoundedCornerShape(2.dp))) {
                        val done = if (total == 0) 0f else (total - left).toFloat() / total
                        Box(Modifier.fillMaxWidth(done).height(4.dp).background(c.disc, RoundedCornerShape(2.dp)))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiseChip("Pending", !showAll) { showAll = false }
                MiseChip("All", showAll) { showAll = true }
            }

            if (total == 0 && staples.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                EmptyState(
                    if (dishes == 0) "Nothing planned this week" else "No ingredients yet",
                    if (dishes == 0) "Plan dishes in Plan and the list builds itself, merged by aisle." else "The planned recipes have no ingredients. Add some in Recipes.",
                )
            }

            val grouped = main.groupBy { d.aisles[it.key] ?: Aisle.guess(it.name) }
            Aisle.entries.forEach { aisle ->
                val lines = grouped[aisle].orEmpty()
                val visible = if (showAll) lines else lines.filter { "ing:${it.key}" !in d.checked }
                if (visible.isEmpty()) return@forEach
                SectionLabel(aisle.label, visible.size.toString())
                Card {
                    visible.forEachIndexed { i, line ->
                        if (i > 0) Divider()
                        val key = "ing:${line.key}"
                        val checked = key in d.checked
                        ItemRow(line.name, line.dishes.joinToString(", "), line.amount, checked,
                            onToggle = { toggle(key, !checked) },
                            onLongPress = { options = line },
                        )
                    }
                }
            }

            val extras = if (showAll) d.extras else d.extras.filter { !it.checked }
            if (extras.isNotEmpty()) {
                SectionLabel("Added by hand", extras.size.toString())
                Card {
                    extras.forEachIndexed { i, e ->
                        if (i > 0) Divider()
                        ItemRow(e.name, "", e.qty, e.checked,
                            onToggle = { repo.scope.launch { repo.setExtraChecked(e.id, !e.checked) } },
                            onLongPress = { repo.scope.launch { repo.removeExtra(e.id) } },
                            longPressLabel = "Remove",
                        )
                    }
                }
            }

            if (staples.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { showStaples = !showStaples }.padding(top = 18.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("STAPLES YOU USUALLY HAVE", style = Mise.type.label, color = c.text2, modifier = Modifier.weight(1f))
                    Text(if (showStaples) "HIDE" else "SHOW ${staples.size}", style = Mise.type.label, color = c.text)
                }
                if (showStaples) {
                    Card {
                        staples.forEachIndexed { i, line ->
                            if (i > 0) Divider()
                            val key = "ing:${line.key}"
                            val checked = key in d.checked
                            ItemRow(line.name, line.dishes.joinToString(", "), line.amount, checked,
                                onToggle = { toggle(key, !checked) },
                                onLongPress = { options = line },
                            )
                        }
                    }
                }
            }
            if (total > 0) {
                Text(
                    "Tap to tick. Press and hold an item to move it to another aisle or mark it as a staple.",
                    style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                )
            }
        }
    }

    options?.let { line ->
        val d = data
        ItemOptionsSheet(
            line = line,
            aisle = d?.aisles?.get(line.key) ?: Aisle.guess(line.name),
            staple = d?.staples?.contains(line.key) == true,
            onAisle = { a -> repo.scope.launch { repo.setAisle(line.key, a) } },
            onStaple = { s -> repo.scope.launch { repo.setStaple(line.key, s) } },
            onDismiss = { options = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    name: String,
    sub: String,
    amount: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    longPressLabel: String = "Options",
) {
    val c = Mise.colours
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(role = Role.Checkbox, onLongClickLabel = longPressLabel, onLongClick = onLongPress, onClick = onToggle)
            .semantics { stateDescription = if (checked) "Ticked" else "Not ticked" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp)
                .background(if (checked) c.control else androidx.compose.ui.graphics.Color.Transparent, shape)
                .border(BorderStroke(1.5.dp, if (checked) c.control else c.text2), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(MiseIcons.Check, contentDescription = null, tint = c.onControl, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, style = Mise.type.bodyM.copy(textDecoration = if (checked) TextDecoration.LineThrough else null),
                color = if (checked) c.text2 else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) Text(sub, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (amount.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(amount, style = Mise.type.label.copy(letterSpacing = Mise.type.body.letterSpacing), color = if (checked) c.text2 else c.text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemOptionsSheet(
    line: GroceryLine,
    aisle: Aisle,
    staple: Boolean,
    onAisle: (Aisle) -> Unit,
    onStaple: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mise.colours
    var current by remember { mutableStateOf(aisle) }
    var isStaple by remember { mutableStateOf(staple) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.amount.uppercase(), style = Mise.type.labelS, color = c.text2)
                    Text(line.name, style = Mise.type.displayS, color = c.text)
                }
                IconTile(MiseIcons.Close, "Close", onDismiss)
            }
            if (line.dishes.isNotEmpty()) {
                Text("For " + line.dishes.joinToString(", "), style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(top = 8.dp))
            }
            SectionLabel("Aisle")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Aisle.entries.forEach { a ->
                    MiseChip(a.label, a == current) { current = a; onAisle(a) }
                }
            }
            SectionLabel("Staple")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { isStaple = !isStaple; onStaple(isStaple) }
                    .background(c.soft, CardShape).border(BorderStroke(1.dp, c.line), CardShape).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("We usually have this", style = Mise.type.bodyM, color = c.text)
                    Text("Staples move to the bottom, out of the way. Pantry stock arrives in a later update.", style = Mise.type.bodyS, color = c.text2)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(width = 44.dp, height = 26.dp).background(if (isStaple) c.control else c.line, RoundedCornerShape(13.dp)).padding(3.dp),
                    contentAlignment = if (isStaple) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.size(20.dp).background(if (isStaple) c.onControl else c.surface, RoundedCornerShape(10.dp))) }
            }
        }
    }
}
