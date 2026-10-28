package app.mise.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Choices
import app.mise.data.Grocery
import app.mise.data.Meal
import app.mise.data.PastWeek
import app.mise.data.PlanEntry
import app.mise.data.PlanNote
import app.mise.data.PlanRepo
import app.mise.data.Prefs
import app.mise.data.Recipe
import app.mise.data.RecipeRepo
import app.mise.data.RecipeSummary
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

fun thisWeekStart(): LocalDate = Grocery.weekStartOf(LocalDate.now(), Prefs.weekStart)

fun weekTitle(weekStart: LocalDate): String {
    val thisWeek = thisWeekStart()
    return when (weekStart) {
        thisWeek -> "This week"
        thisWeek.plusWeeks(1) -> "Next week"
        thisWeek.minusWeeks(1) -> "Last week"
        else -> "Week of " + weekStart.format(DayMonth)
    }
}

fun weekRange(weekStart: LocalDate) = weekStart.format(DayMonth) + " to " + weekStart.plusDays(6).format(DayMonth)

fun dayName(d: LocalDate, full: Boolean = true): String = d.dayOfWeek.getDisplayName(if (full) TextStyle.FULL else TextStyle.SHORT, Locale.UK)

/** "Wednesday dinner", "Today's lunch" style label for a slot. */
fun slotLabel(date: LocalDate, meal: Meal): String {
    val today = LocalDate.now()
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> dayName(date)
    }
    return "$day ${meal.label.lowercase()}"
}

/** Previous week, the dates (and a way back to this week), next week. */
@Composable
fun WeekNav(weekStart: LocalDate, onWeek: (LocalDate) -> Unit) {
    val c = Mise.colours
    val current = thisWeekStart()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(MiseIcons.Back, "Previous week") { onWeek(weekStart.minusWeeks(1)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (weekStart == current) {
                Text(weekRange(weekStart).uppercase(), style = Mise.type.label, color = c.text2, textAlign = TextAlign.Center, maxLines = 1)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(weekRange(weekStart).uppercase(), style = Mise.type.labelS, color = c.text2, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Pill("This week", filled = true) { onWeek(current) }
                }
            }
        }
        IconTile(MiseIcons.Forward, "Next week") { onWeek(weekStart.plusWeeks(1)) }
    }
}

/** What the plan sheet is doing. */
sealed interface PlanSheet {
    /** Add to a slot; [recipeId] preselects a dish (from a recipe page). */
    data class Add(val date: LocalDate, val meal: Meal, val recipeId: Long? = null, val servings: Int? = null, val choices: List<String>? = null) : PlanSheet
    data class Change(val entry: PlanEntry) : PlanSheet
    data class Note(val date: LocalDate, val meal: Meal, val note: PlanNote? = null) : PlanSheet
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlanScreen(
    weekStart: LocalDate,
    onWeek: (LocalDate) -> Unit,
    onSettings: () -> Unit,
    onShop: () -> Unit,
    onGoToRecipes: () -> Unit,
    onOpenPlanned: (PlanEntry) -> Unit,
    sheet: PlanSheet?,
    onSheet: (PlanSheet?) -> Unit,
) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val entries by remember(weekStart) { repo.week(weekStart) }.collectAsState(initial = null)
    val notes by remember(weekStart) { repo.notes(weekStart) }.collectAsState(initial = emptyList())
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val today = LocalDate.now()
    var repeat by remember { mutableStateOf(false) }
    val longPress = Haptic.rememberLongPress()

    Column(Modifier.fillMaxSize()) {
        val list = entries.orEmpty()
        val dishes = list.count { !it.leftovers }
        ScreenHeader(
            eyebrow = if (dishes == 0) "Plan" else if (dishes == 1) "1 dish planned" else "$dishes dishes planned",
            title = weekTitle(weekStart),
            actionIcon = MiseIcons.Settings, actionLabel = "Settings", onAction = onSettings,
        )
        WeekNav(weekStart, onWeek)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            if (entries == null) return@LazyColumn
            items(days, key = { it.toString() }) { d ->
                val dayEntries = list.filter { it.date == d }.sortedWith(compareBy({ it.meal.ordinal }, { it.createdAt }))
                val dayNotes = notes.filter { it.date == d }.sortedBy { it.meal.ordinal }
                val past = d.isBefore(today)
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CappedText(1.3f) {
                            Text(dayName(d, full = false).uppercase() + "  " + d.dayOfMonth, style = Mise.type.label, color = if (past) c.text2 else c.text)
                        }
                        if (d == today) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Today", dot = true)
                        }
                        Spacer(Modifier.weight(1f))
                        if (dayEntries.isNotEmpty() || dayNotes.isNotEmpty()) {
                            Box(
                                Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = "Add to ${dayName(d)}") {
                                    onSheet(PlanSheet.Add(d, firstFreeMeal(d, list, notes)))
                                },
                                contentAlignment = Alignment.Center,
                            ) { Icon(MiseIcons.Create, contentDescription = "Add to ${dayName(d)}", tint = c.text2, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    if (dayEntries.isEmpty() && dayNotes.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .background(c.surface.copy(alpha = if (c.isDark) 0.5f else 0.6f), CardShape)
                                .clickable(role = Role.Button) { onSheet(PlanSheet.Add(d, Meal.DINNER)) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("Plan ${dayName(d)}", style = Mise.type.body, color = c.text2)
                        }
                    } else {
                        Card {
                            var first = true
                            dayEntries.forEach { e ->
                                if (!first) Divider()
                                first = false
                                PlanRow(
                                    e, dim = past,
                                    onOpen = { onOpenPlanned(e) },
                                    onChange = { longPress(); onSheet(PlanSheet.Change(e)) },
                                )
                            }
                            dayNotes.forEach { n ->
                                if (!first) Divider()
                                first = false
                                NoteRow(n, dim = past) { onSheet(PlanSheet.Note(n.date, n.meal, n)) }
                            }
                        }
                    }
                }
            }
            item(key = "repeat") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                    TextAction("Repeat a past week", { repeat = true })
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            val label = if (weekStart == thisWeekStart()) "Shop this week" else "Shop " + weekTitle(weekStart).replaceFirstChar { it.lowercase() }
            PrimaryButton(
                text = if (dishes == 0) "Plan a dish to start the list" else "$label · " + (if (dishes == 1) "1 dish" else "$dishes dishes"),
                onClick = onShop,
                enabled = dishes > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    sheet?.let { s ->
        PlanEntrySheet(
            mode = s,
            days = days,
            onDismiss = { onSheet(null) },
            onGoToRecipes = { onSheet(null); onGoToRecipes() },
        )
    }
    if (repeat) RepeatWeekSheet(weekStart, onDismiss = { repeat = false })
}

private fun firstFreeMeal(d: LocalDate, entries: List<PlanEntry>, notes: List<PlanNote>): Meal {
    val used = entries.filter { it.date == d }.map { it.meal } + notes.filter { it.date == d }.map { it.meal }
    return listOf(Meal.DINNER, Meal.LUNCH, Meal.BREAKFAST, Meal.SNACK).firstOrNull { it !in used } ?: Meal.DINNER
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(e: PlanEntry, dim: Boolean, onOpen: () -> Unit, onChange: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .combinedClickable(role = Role.Button, onClickLabel = "Open recipe", onLongClickLabel = "Change", onClick = onOpen, onLongClick = onChange)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(e.recipe.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.meal.label.uppercase(), style = Mise.type.labelS, color = c.text2)
            Text(e.recipe.title, style = Mise.type.bodyM, color = if (dim) c.text2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (e.leftovers) "Leftovers · nothing to buy" else (listOf("${e.servings} servings") + e.chosenOptions.map { it.label }).joinToString(" · ")
            Text(sub, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Change ${e.recipe.title}", onClick = onChange),
            contentAlignment = Alignment.Center,
        ) { Icon(MiseIcons.More, contentDescription = "Change ${e.recipe.title}", tint = c.text2, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun NoteRow(n: PlanNote, dim: Boolean, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(MiseIcons.Edit, contentDescription = null, tint = c.text2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.meal.label.uppercase(), style = Mise.type.labelS, color = c.text2)
            Text(n.text, style = Mise.type.body, color = if (dim) c.text2 else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Day chips and meal chips: where the dish goes. */
@Composable
fun SlotPicker(days: List<LocalDate>, date: LocalDate, meal: Meal, onDate: (LocalDate) -> Unit, onMeal: (Meal) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { d -> MiseChip(dayName(d, full = false) + " " + d.dayOfMonth, d == date) { onDate(d) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Meal.entries.forEach { m -> MiseChip(m.label, m == meal) { onMeal(m) } }
        }
    }
}

/**
 * Adding, changing or noting a slot. Adding keeps the sheet open: after each dish it moves to
 * the next free day for the same meal, so a whole week is planned in one go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEntrySheet(mode: PlanSheet, days: List<LocalDate>, onDismiss: () -> Unit, onGoToRecipes: () -> Unit, keepOpen: Boolean = true) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val recipes = RecipeRepo.get(ctx)
    val plan = PlanRepo.get(ctx)
    val scope = rememberCoroutineScope()
    val confirm = Haptic.rememberConfirm()

    val startDate = when (mode) { is PlanSheet.Add -> mode.date; is PlanSheet.Change -> mode.entry.date; is PlanSheet.Note -> mode.date }
    val startMeal = when (mode) { is PlanSheet.Add -> mode.meal; is PlanSheet.Change -> mode.entry.meal; is PlanSheet.Note -> mode.meal }
    var date by remember { mutableStateOf(startDate) }
    var meal by remember { mutableStateOf(startMeal) }
    var noteMode by remember { mutableStateOf(mode is PlanSheet.Note) }
    var noteText by remember { mutableStateOf((mode as? PlanSheet.Note)?.note?.text ?: "") }
    var recipe by remember { mutableStateOf((mode as? PlanSheet.Change)?.entry?.recipe) }
    var pendingId by remember { mutableStateOf((mode as? PlanSheet.Add)?.recipeId) }
    var servings by remember { mutableIntStateOf((mode as? PlanSheet.Change)?.entry?.servings ?: (mode as? PlanSheet.Add)?.servings ?: 4) }
    var choices by remember { mutableStateOf((mode as? PlanSheet.Change)?.entry?.choices ?: (mode as? PlanSheet.Add)?.choices ?: emptyList()) }
    var leftovers by remember { mutableStateOf((mode as? PlanSheet.Change)?.entry?.leftovers ?: false) }
    var lastAdded by remember { mutableStateOf<String?>(null) }
    val week by remember(days.first()) { plan.week(days.first()) }.collectAsState(initial = emptyList())
    val presetServings = (mode as? PlanSheet.Add)?.servings
    val presetChoices = (mode as? PlanSheet.Add)?.choices

    LaunchedEffect(pendingId) {
        val id = pendingId ?: return@LaunchedEffect
        val r = recipes.get(id) ?: return@LaunchedEffect
        recipe = r
        servings = presetServings ?: plan.defaultServings(r)
        choices = presetChoices ?: Choices.decode("", r)
        pendingId = null
    }

    fun choose(id: Long) {
        scope.launch {
            val r = recipes.get(id) ?: return@launch
            recipe = r
            servings = plan.defaultServings(r)
            choices = Choices.decode("", r)
            leftovers = false
        }
    }

    val isChange = mode is PlanSheet.Change
    val title = when {
        noteMode -> if ((mode as? PlanSheet.Note)?.note != null) "Change note" else "Eating out or a note"
        isChange -> "Change dish"
        recipe == null -> "Choose a dish"
        else -> "Add to the plan"
    }

    MiseSheet(title = title, eyebrow = slotLabel(date, meal), onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            lastAdded?.let {
                Text(it, style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            }
            SlotPicker(days, date, meal, onDate = { date = it }, onMeal = { meal = it })
            Spacer(Modifier.height(12.dp))
            val r = recipe
            when {
                noteMode -> {
                    MiseField(noteText, { noteText = it.take(80) }, "Eating out, takeaway, at Mum's", Modifier.fillMaxWidth(), imeAction = ImeAction.Done)
                    Spacer(Modifier.height(12.dp))
                    val existing = (mode as? PlanSheet.Note)?.note
                    PrimaryButton(if (existing != null) "Save" else "Add to ${slotLabel(date, meal)}", {
                        val t = noteText.trim()
                        if (t.isEmpty()) return@PrimaryButton
                        plan.scope.launch { if (existing != null) plan.updateNote(existing.id, date, meal, t) else plan.addNote(date, meal, t) }
                        confirm()
                        onDismiss()
                    }, Modifier.fillMaxWidth(), enabled = noteText.isNotBlank())
                    Spacer(Modifier.height(8.dp))
                    if (existing != null) {
                        GhostButton("Remove", {
                            plan.scope.launch {
                                val gone = plan.removeNote(existing.id)
                                if (gone != null) Toasts.undo("Removed “${gone.text}”") { plan.scope.launch { plan.restoreNote(gone) } }
                            }
                            onDismiss()
                        }, Modifier.fillMaxWidth(), danger = true)
                    } else {
                        GhostButton("Choose a dish instead", { noteMode = false }, Modifier.fillMaxWidth())
                    }
                }
                r == null && pendingId != null -> Unit
                r == null -> DishChooser(
                    onPick = { choose(it) },
                    onNote = { noteMode = true },
                    onGoToRecipes = onGoToRecipes,
                )
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Monogram(r.title)
                        Spacer(Modifier.width(12.dp))
                        Text(r.title, style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextAction("Change", { recipe = null })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!leftovers) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Servings", style = Mise.type.bodyM, color = c.text)
                                if (servings != r.baseServings) Text("Recipe is for ${r.baseServings}", style = Mise.type.bodyS, color = c.text2)
                            }
                            Stepper(servings, { servings = it }, label = "servings")
                        }
                        r.slots.forEachIndexed { si, slot ->
                            SectionLabel(slot.name, "swap")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                slot.options.forEach { o ->
                                    MiseChip(o.label, choices.getOrNull(si) == o.label) {
                                        val base = if (choices.size == r.slots.size) choices else Choices.decode("", r)
                                        choices = base.mapIndexed { k, v -> if (k == si) o.label else v }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    MiseSwitch(leftovers, { leftovers = it }, "Leftovers", sub = "Shows on the plan, adds nothing to the list", inset = 4.dp)
                    Spacer(Modifier.height(12.dp))
                    if (mode is PlanSheet.Change) {
                        PrimaryButton("Save", {
                            val id = r.id; val sv = servings; val ch = choices; val lo = leftovers; val d = date; val m = meal
                            plan.scope.launch { plan.updateEntry(mode.entry.id, d, m, id, sv, ch, lo) }
                            confirm()
                            onDismiss()
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Remove from the plan", {
                            plan.scope.launch {
                                val gone = plan.removeEntry(mode.entry.id)
                                if (gone != null) Toasts.undo("Removed ${r.title}") { plan.scope.launch { plan.restoreEntry(gone) } }
                            }
                            onDismiss()
                        }, Modifier.fillMaxWidth(), danger = true)
                    } else {
                        PrimaryButton("Add to ${slotLabel(date, meal)}", {
                            val id = r.id; val sv = servings; val ch = choices; val lo = leftovers; val d = date; val m = meal; val t = r.title
                            plan.scope.launch {
                                val newId = plan.addEntry(d, m, id, sv, ch, lo)
                                Toasts.undo("Added $t to ${slotLabel(d, m)}") { plan.scope.launch { plan.removeEntry(newId) } }
                            }
                            confirm()
                            if (!keepOpen) { onDismiss(); return@PrimaryButton }
                            // Move on to the next free day for the same meal, ready for the next dish.
                            val taken = week.filter { it.meal == m }.map { it.date }.toSet() + d
                            val next = days.firstOrNull { it.isAfter(d) && it !in taken }
                            lastAdded = "Added $t to ${slotLabel(d, m)}." + if (next != null) " Next: ${slotLabel(next, m)}." else ""
                            if (next != null) date = next
                            recipe = null
                            leftovers = false
                        }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * Search, recent dishes, and the ones you have not had for a while (with when you last had
 * them), then everything A to Z.
 */
@Composable
private fun DishChooser(onPick: (Long) -> Unit, onNote: () -> Unit, onGoToRecipes: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val recipes = RecipeRepo.get(ctx)
    val plan = PlanRepo.get(ctx)
    val summaries by remember { recipes.summaries() }.collectAsState(initial = null)
    val recentIds by remember { plan.recentRecipeIds() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val list = summaries ?: return
    if (list.isEmpty()) {
        EmptyState("No recipes yet", "Write or import a recipe first, then come back to plan it.")
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Go to Recipes", onGoToRecipes, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        GhostButton("Eating out or a note", onNote, Modifier.fillMaxWidth())
        return
    }
    MiseField(query, { query = it }, "Search dishes or ingredients", Modifier.fillMaxWidth(), imeAction = ImeAction.Search)
    Spacer(Modifier.height(4.dp))
    val q = query.trim().lowercase()
    if (q.isNotEmpty()) {
        val hits = list.filter { it.searchText.contains(q) }
        if (hits.isEmpty()) Text("Nothing matches “${query.trim()}”.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        hits.forEach { ChooserRow(it) { onPick(it.id) } }
        return
    }
    AddRow("Eating out or a note", onClick = onNote)
    val byId = list.associateBy { it.id }
    val recent = recentIds.mapNotNull { byId[it] }.take(5)
    if (recent.isNotEmpty()) {
        SectionLabel("Recent")
        recent.forEach { ChooserRow(it) { onPick(it.id) } }
    }
    val forgotten = list.filter { it.id !in recentIds }.sortedWith(compareBy(nullsFirst<String>()) { it.lastPlanned }).take(6)
    if (forgotten.isNotEmpty()) {
        SectionLabel("Not had for a while")
        forgotten.forEach { ChooserRow(it, showLast = true) { onPick(it.id) } }
    }
    SectionLabel("All dishes", list.size.toString())
    list.forEach { ChooserRow(it) { onPick(it.id) } }
}

@Composable
private fun ChooserRow(s: RecipeSummary, showLast: Boolean = false, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(s.title, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = Mise.type.bodyM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (showLast) add(agoText(s.lastPlanned)?.let { "Last had $it" } ?: "Not planned yet")
                if (s.dishType.isNotBlank()) add(s.dishType)
                if (s.slotCount > 0) add("swaps")
            }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Copies a past week's dishes, day for day, into the week on screen. */
@Composable
private fun RepeatWeekSheet(target: LocalDate, onDismiss: () -> Unit) {
    val c = Mise.colours
    val plan = PlanRepo.get(LocalContext.current)
    var weeks by remember { mutableStateOf<List<PastWeek>?>(null) }
    LaunchedEffect(target) { weeks = plan.pastWeeks(target) }
    MiseSheet(title = "Repeat a past week", eyebrow = "Into " + weekTitle(target).replaceFirstChar { it.lowercase() }, onDismiss = onDismiss) {
        val w = weeks
        when {
            w == null -> Unit
            w.isEmpty() -> EmptyState("No past weeks yet", "Once you have planned a week, you can repeat it here.")
            else -> Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Dishes go to the same days. Anything already planned stays.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                w.forEach { pw ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                            plan.scope.launch {
                                val n = plan.copyWeek(pw.weekStart, target)
                                Toasts.show(if (n == 0) "Nothing new to copy" else if (n == 1) "Copied 1 dish" else "Copied $n dishes")
                            }
                            onDismiss()
                        }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(weekTitle(pw.weekStart), style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f))
                        Text(if (pw.dishes == 1) "1 DISH" else "${pw.dishes} DISHES", style = Mise.type.labelS, color = c.text2)
                    }
                    Divider()
                }
            }
        }
    }
}
