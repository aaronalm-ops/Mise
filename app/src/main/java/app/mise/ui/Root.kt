package app.mise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mise.data.Choices
import app.mise.data.Meal
import app.mise.data.Prefs
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate

/** "recipe:12?s=6&c=Chicken" -> ("recipe", 12, {s=6, c=Chicken}). */
private data class Route(val name: String, val id: Long?, val args: Map<String, String>)

private fun parse(r: String): Route {
    val name = r.substringBefore(':')
    val rest = r.substringAfter(':', "")
    val idPart = rest.substringBefore('?')
    val args = rest.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    return Route(name, idPart.toLongOrNull(), args)
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** The week to open: this week, or next week once the weekend (the last two days) arrives. */
private fun defaultWeek(start: DayOfWeek): LocalDate {
    val today = LocalDate.now()
    val ws = app.mise.data.Grocery.weekStartOf(today, start)
    return if (today >= ws.plusDays(5)) ws.plusWeeks(1) else ws
}

/**
 * Screens above the tabs, kept as a small stack of route strings so it survives the app being
 * closed by the phone: "settings", "household", "recipe:12?s=6&c=…", "cook:12?…", "edit:new",
 * "edit:12", "draft:3".
 */
@Composable
fun MiseRoot(open: String? = null, onOpened: () -> Unit = {}) {
    val c = Mise.colours
    var dest by rememberSaveable { mutableStateOf(Dest.Plan) }
    var stackText by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showAddItem by rememberSaveable { mutableStateOf(false) }
    var aiRecipe by rememberSaveable { mutableStateOf("") }
    var weekText by rememberSaveable(Prefs.weekStart) { mutableStateOf(defaultWeek(Prefs.weekStart).toString()) }
    var planSheet by remember { mutableStateOf<PlanSheet?>(null) }
    // Keeps each tab's scroll position and filters while another screen is on top.
    val holder = rememberSaveableStateHolder()
    val weekStart = LocalDate.parse(weekText)
    val setWeek: (LocalDate) -> Unit = { weekText = it.toString() }

    val stack = stackText.split('|').filter { it.isNotEmpty() }
    fun setStack(list: List<String>) { stackText = list.joinToString("|") }
    fun push(r: String) = setStack(stack + r)
    fun pop() = setStack(stack.dropLast(1))
    fun replaceTop(r: String) = setStack(stack.dropLast(1) + r)

    LaunchedEffect(open) {
        when (open) {
            null -> return@LaunchedEffect
            "list" -> { setStack(emptyList()); dest = Dest.List }
            "plan" -> { setStack(emptyList()); dest = Dest.Plan }
            "plan-next" -> { setStack(emptyList()); dest = Dest.Plan; weekText = thisWeekStart().plusWeeks(1).toString() }
            "new-recipe" -> { dest = Dest.Recipes; setStack(listOf("edit:new")) }
        }
        onOpened()
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        val top = stack.lastOrNull()
        AnimatedContent(
            targetState = top,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            // The editor keeps one key while its route moves from "edit:new" to "draft:3".
            contentKey = { it?.let { r -> if (parse(r).name in setOf("edit", "draft")) "editor" else r } },
            label = "screens",
        ) { route ->
            Box(Modifier.fillMaxSize().background(c.base)) {
                if (route == null) {
                    Tabs(
                        holder = holder,
                        dest = dest, onDest = { dest = it },
                        weekStart = weekStart, setWeek = setWeek,
                        push = ::push,
                        planSheet = planSheet, onPlanSheet = { planSheet = it },
                        onAdd = { showAdd = true },
                        onAddItem = { showAddItem = true },
                        onHousehold = { setStack(listOf("settings", "household")) },
                    )
                } else {
                    Stacked(
                        route = route,
                        stackSize = stack.size,
                        pop = ::pop,
                        push = ::push,
                        replaceTop = ::replaceTop,
                        onSaved = { id ->
                            setStack(stack.dropLast(1).filter { parse(it).let { p -> !(p.name == "recipe" && p.id == id) } } + "recipe:$id")
                            dest = Dest.Recipes
                        },
                        onDeleted = { id ->
                            setStack(stack.filter { parse(it).let { p -> p.id != id || p.name !in setOf("recipe", "cook", "edit") } }.dropLastWhile { it.startsWith("draft:") || it == "edit:new" })
                        },
                    )
                }
            }
        }

        ToastHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (top == null) 68.dp else 80.dp))

        if (showAdd) {
            AddSheet(
                onDismiss = { showAdd = false },
                onNewRecipe = { showAdd = false; dest = Dest.Recipes; push("edit:new") },
                onListItem = { showAdd = false; showAddItem = true },
                onPhoto = { showAdd = false; aiRecipe = AiRecipeMode.Photo.name },
                onIdea = { showAdd = false; aiRecipe = AiRecipeMode.Idea.name },
                onPlanDish = {
                    showAdd = false
                    setStack(emptyList())
                    dest = Dest.Plan
                    val today = LocalDate.now()
                    val d = if (today in weekStart..weekStart.plusDays(6)) today else weekStart
                    planSheet = PlanSheet.Add(d, Meal.DINNER)
                },
            )
        }
        if (aiRecipe.isNotEmpty()) {
            AiRecipeSheet(
                mode = AiRecipeMode.valueOf(aiRecipe),
                onDismiss = { aiRecipe = "" },
                onDraft = { id -> aiRecipe = ""; dest = Dest.Recipes; setStack(listOf("draft:$id")) },
                onHousehold = { aiRecipe = ""; setStack(listOf("settings", "household")) },
            )
        }
        if (showAddItem) {
            AddItemSheet(weekStart, onDone = { showAddItem = false })
        }
    }
}

@Composable
private fun Tabs(
    holder: SaveableStateHolder,
    dest: Dest,
    onDest: (Dest) -> Unit,
    weekStart: LocalDate,
    setWeek: (LocalDate) -> Unit,
    push: (String) -> Unit,
    planSheet: PlanSheet?,
    onPlanSheet: (PlanSheet?) -> Unit,
    onAdd: () -> Unit,
    onAddItem: () -> Unit,
    onHousehold: () -> Unit,
) {
    BackHandler(enabled = dest != Dest.Plan) { onDest(Dest.Plan) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val openSettings = { push("settings") }
            holder.SaveableStateProvider(dest.name) {
            when (dest) {
                Dest.Plan -> PlanScreen(
                    weekStart = weekStart,
                    onWeek = setWeek,
                    onSettings = openSettings,
                    onShop = { onDest(Dest.List) },
                    onGoToRecipes = { onDest(Dest.Recipes) },
                    onOpenPlanned = { e -> push("recipe:${e.recipe.id}?s=${e.servings}&c=${enc(Choices.encode(e.choices))}") },
                    sheet = planSheet,
                    onSheet = onPlanSheet,
                    onHousehold = onHousehold,
                )
                Dest.Recipes -> RecipesScreen(
                    onSettings = openSettings,
                    onOpen = { push("recipe:$it") },
                    onOpenDraft = { push("draft:$it") },
                    onNew = { push("edit:new") },
                )
                Dest.List -> ListScreen(weekStart, setWeek, openSettings, onAddItem = onAddItem)
                Dest.Pantry -> PantryScreen(openSettings)
            }
            }
        }
        MiseNavBar(current = dest, onSelect = onDest, onAdd = onAdd)
    }
}

@Composable
private fun Stacked(
    route: String,
    stackSize: Int,
    pop: () -> Unit,
    push: (String) -> Unit,
    replaceTop: (String) -> Unit,
    onSaved: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
) {
    val r = parse(route)
    when (r.name) {
        "settings" -> {
            BackHandler { pop() }
            SettingsScreen(onBack = pop, onHousehold = { push("household") })
        }
        "household" -> {
            BackHandler { pop() }
            HouseholdScreen(onBack = pop)
        }
        "recipe" -> {
            val id = r.id ?: return
            BackHandler { pop() }
            RecipeDetailScreen(
                id = id,
                initialServings = r.args["s"]?.toIntOrNull(),
                initialChoices = r.args["c"]?.takeIf { it.isNotEmpty() }?.split(Choices.SEP),
                onBack = pop,
                onEdit = { push("edit:$it") },
                onCook = { s, ch -> push("cook:$id?s=$s&c=${enc(Choices.encode(ch))}") },
            )
        }
        "cook" -> {
            val id = r.id ?: return
            BackHandler { pop() }
            CookModeScreen(id, r.args["s"]?.toIntOrNull(), r.args["c"]?.takeIf { it.isNotEmpty() }?.split(Choices.SEP), onClose = pop)
        }
        "edit", "draft" -> {
            val target = when {
                r.name == "draft" && r.id != null -> EditTarget.Draft(r.id)
                r.id != null -> EditTarget.Existing(r.id)
                else -> EditTarget.New
            }
            // Keyed by stack depth, not route, so pointing the route at the new draft never
            // restarts the editor. The editor handles Back itself.
            key("editor$stackSize") {
                RecipeEditorScreen(
                    target = target,
                    onClose = pop,
                    onSaved = onSaved,
                    onDraftCreated = { replaceTop("draft:$it") },
                    onDeleted = onDeleted,
                )
            }
        }
        else -> LaunchedEffect(route) { pop() }
    }
}
