package app.mise.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Meal
import app.mise.data.PlanEntry
import app.mise.data.PlanRepo
import app.mise.data.Recipe
import app.mise.data.RecipeRepo
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

fun weekTitle(weekStart: LocalDate): String {
    val thisWeek = app.mise.data.Grocery.weekStartOf(LocalDate.now())
    return when (weekStart) {
        thisWeek -> "This week"
        thisWeek.plusWeeks(1) -> "Next week"
        thisWeek.minusWeeks(1) -> "Last week"
        else -> "Week of " + weekStart.format(DayMonth)
    }
}

fun weekRange(weekStart: LocalDate) = weekStart.format(DayMonth) + " to " + weekStart.plusDays(6).format(DayMonth)

/** Previous week, the date range, next week. */
@Composable
fun WeekNav(weekStart: LocalDate, onWeek: (LocalDate) -> Unit) {
    val c = Mise.colours
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(MiseIcons.Back, "Previous week") { onWeek(weekStart.minusWeeks(1)) }
        Text(weekRange(weekStart).uppercase(), style = Mise.type.label, color = c.text2, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconTile(MiseIcons.Forward, "Next week") { onWeek(weekStart.plusWeeks(1)) }
    }
}

/** What the plan sheet is doing: adding to a day and meal, or changing an entry. */
private sealed interface PlanSheet {
    data class Add(val date: LocalDate, val meal: Meal) : PlanSheet
    data class Change(val entry: PlanEntry) : PlanSheet
}

@Composable
fun PlanScreen(
    weekStart: LocalDate,
    onWeek: (LocalDate) -> Unit,
    onSettings: () -> Unit,
    onBuildList: () -> Unit,
    onGoToRecipes: () -> Unit,
) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val entries by remember(weekStart) { repo.week(weekStart) }.collectAsState(initial = emptyList())
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val today = LocalDate.now()
    var selected by rememberSaveable(weekStart) { mutableIntStateOf(days.indexOf(today).takeIf { it >= 0 } ?: 0) }
    var sheet by remember { mutableStateOf<PlanSheet?>(null) }
    val day = days[selected]

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Plan", weekTitle(weekStart), MiseIcons.Settings, "Settings", onSettings)
        WeekNav(weekStart, onWeek)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, d ->
                val isSel = i == selected
                val has = entries.any { it.date == d }
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .height(62.dp)
                        .background(if (isSel) c.control else c.surface, shape)
                        .border(BorderStroke(1.dp, if (isSel) c.control else c.line), shape)
                        .selectable(selected = isSel, role = Role.Tab, onClick = { selected = i }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(), style = Mise.type.labelS, color = if (isSel) c.onControl else c.text2)
                    Spacer(Modifier.height(3.dp))
                    Text(d.dayOfMonth.toString(), style = Mise.type.number, color = if (isSel) c.onControl else c.text)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(5.dp).background(if (has) (if (isSel) c.onControl else c.disc) else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            val dayEntries = entries.filter { it.date == day }
            Text(
                day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK) + " " + day.format(DayMonth),
                style = Mise.type.displayS, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 16.dp),
            )
            Meal.entries.forEach { meal ->
                val list = dayEntries.filter { it.meal == meal }
                SectionLabel(meal.label, if (list.isEmpty()) null else list.size.toString())
                if (list.isNotEmpty()) {
                    Card {
                        list.forEachIndexed { i, e ->
                            if (i > 0) Divider()
                            PlanRow(e) { sheet = PlanSheet.Change(e) }
                        }
                    }
                }
                AddRow("Add ${meal.label.lowercase()}") { sheet = PlanSheet.Add(day, meal) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            val n = entries.size
            PrimaryButton(
                text = if (n == 0) "Plan a dish to build the list" else "Build grocery list · " + (if (n == 1) "1 dish" else "$n dishes"),
                onClick = onBuildList,
                enabled = n > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    sheet?.let { s -> PlanEntrySheet(s, onDismiss = { sheet = null }, onGoToRecipes = { sheet = null; onGoToRecipes() }) }
}

@Composable
private fun PlanRow(e: PlanEntry, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(e.recipe.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.recipe.title, style = Mise.type.bodyM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = (listOf("${e.servings} servings") + e.chosenOptions.map { it.label }).joinToString(" · ")
            Text(sub, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlanEntrySheet(mode: PlanSheet, onDismiss: () -> Unit, onGoToRecipes: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val recipes = RecipeRepo.get(ctx)
    val plan = PlanRepo.get(ctx)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var recipeId by remember { mutableStateOf((mode as? PlanSheet.Change)?.entry?.recipe?.id) }
    var recipe by remember { mutableStateOf<Recipe?>((mode as? PlanSheet.Change)?.entry?.recipe) }
    var servings by remember { mutableIntStateOf((mode as? PlanSheet.Change)?.entry?.servings ?: 4) }
    var choices by remember { mutableStateOf((mode as? PlanSheet.Change)?.entry?.let { e -> e.recipe.slots.indices.map { i -> e.choices.getOrNull(i) ?: e.recipe.slots[i].options.indexOfFirst { it.isDefault }.coerceAtLeast(0) } } ?: emptyList()) }
    var query by remember { mutableStateOf("") }
    val summaries by remember { recipes.summaries() }.collectAsState(initial = null)

    LaunchedEffect(recipeId) {
        val id = recipeId ?: return@LaunchedEffect
        if (recipe?.id == id) return@LaunchedEffect
        val r = recipes.get(id) ?: return@LaunchedEffect
        recipe = r
        servings = r.baseServings
        choices = r.slots.map { s -> s.options.indexOfFirst { it.isDefault }.coerceAtLeast(0) }
    }

    val (date, meal) = when (mode) {
        is PlanSheet.Add -> mode.date to mode.meal
        is PlanSheet.Change -> mode.entry.date to mode.entry.meal
    }
    val where = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK) + " " + meal.label.lowercase()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(where.uppercase(), style = Mise.type.labelS, color = c.text2)
                    Text(
                        when {
                            mode is PlanSheet.Change -> "Change dish"
                            recipeId == null -> "Choose a dish"
                            else -> "Add dish"
                        },
                        style = Mise.type.displayS, color = c.text,
                    )
                }
                IconTile(MiseIcons.Close, "Close", onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            val r = recipe
            if (recipeId == null) {
                val list = summaries
                when {
                    list == null -> Unit
                    list.isEmpty() -> {
                        EmptyState("No recipes yet", "Write a recipe first, then come back to plan it.")
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Go to Recipes", onGoToRecipes, Modifier.fillMaxWidth())
                    }
                    else -> {
                        MiseField(query, { query = it }, "Search recipes", Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            list.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.cuisine.contains(query.trim(), true) }.forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { recipeId = s.id }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Monogram(s.title)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.title, style = Mise.type.bodyM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Serves ${s.baseServings}" + if (s.slotCount > 0) " · swaps" else "", style = Mise.type.bodyS, color = c.text2)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (r != null) {
                Text(r.title, style = Mise.type.bodyM, color = c.text)
                Spacer(Modifier.height(10.dp))
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
                        slot.options.forEachIndexed { oi, o ->
                            MiseChip(o.label, choices.getOrElse(si) { 0 } == oi) {
                                choices = (0 until r.slots.size).map { k -> if (k == si) oi else choices.getOrElse(k) { 0 } }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                when (mode) {
                    is PlanSheet.Add -> {
                        PrimaryButton("Add to $where", {
                            val id = r.id; val sv = servings; val ch = choices
                            plan.scope.launch { plan.addEntry(date, meal, id, sv, ch) }
                            onDismiss()
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Choose a different dish", { recipeId = null; recipe = null }, Modifier.fillMaxWidth())
                    }
                    is PlanSheet.Change -> {
                        PrimaryButton("Save", {
                            val sv = servings; val ch = choices
                            plan.scope.launch { plan.updateEntry(mode.entry.id, sv, ch) }
                            onDismiss()
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Remove from plan", {
                            plan.scope.launch { plan.removeEntry(mode.entry.id) }
                            onDismiss()
                        }, Modifier.fillMaxWidth(), danger = true)
                    }
                }
            }
        }
    }
}
