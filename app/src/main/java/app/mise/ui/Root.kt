package app.mise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.mise.data.Grocery
import java.time.LocalDate

/**
 * Screens above the tabs, kept as a small stack of route strings so it survives rotation and
 * process death: "settings", "recipe:12", "edit:new", "edit:12", "draft:3".
 */
@Composable
fun MiseRoot() {
    val c = Mise.colours
    var dest by rememberSaveable { mutableStateOf(Dest.Plan) }
    var stackText by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showAddItem by rememberSaveable { mutableStateOf(false) }
    var splash by rememberSaveable { mutableStateOf(true) }
    var weekText by rememberSaveable { mutableStateOf(Grocery.weekStartOf(LocalDate.now()).toString()) }
    val weekStart = LocalDate.parse(weekText)
    val setWeek: (LocalDate) -> Unit = { weekText = it.toString() }

    val stack = stackText.split('|').filter { it.isNotEmpty() }
    fun push(r: String) { stackText = (stack + r).joinToString("|") }
    fun pop() { stackText = stack.dropLast(1).joinToString("|") }
    fun replaceTop(r: String) { stackText = (stack.dropLast(1) + r).joinToString("|") }

    Box(Modifier.fillMaxSize().background(c.base)) {
        val top = stack.lastOrNull()
        when {
            top == null -> {
                BackHandler(enabled = dest != Dest.Plan) { dest = Dest.Plan }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val open = { push("settings") }
                        when (dest) {
                            Dest.Plan -> PlanScreen(
                                weekStart = weekStart,
                                onWeek = setWeek,
                                onSettings = open,
                                onBuildList = { dest = Dest.List },
                                onGoToRecipes = { dest = Dest.Recipes },
                            )
                            Dest.Recipes -> RecipesScreen(
                                onSettings = open,
                                onOpen = { push("recipe:$it") },
                                onOpenDraft = { push("draft:$it") },
                                onNew = { push("edit:new") },
                            )
                            Dest.List -> ListScreen(weekStart, setWeek, open, onAddItem = { showAddItem = true })
                            Dest.Pantry -> PantryScreen(open)
                        }
                    }
                    MiseNavBar(current = dest, onSelect = { dest = it }, onAdd = { showAdd = true })
                }
            }
            top == "settings" -> {
                BackHandler { pop() }
                SettingsScreen(onBack = { pop() }, onHousehold = { push("household") })
            }
            top == "household" -> {
                BackHandler { pop() }
                HouseholdScreen(onBack = { pop() })
            }
            top.startsWith("recipe:") -> {
                val id = top.removePrefix("recipe:").toLong()
                BackHandler { pop() }
                RecipeDetailScreen(id, onBack = { pop() }, onEdit = { push("edit:$it") })
            }
            top.startsWith("edit:") || top.startsWith("draft:") -> {
                val target = when {
                    top == "edit:new" -> EditTarget.New
                    top.startsWith("draft:") -> EditTarget.Draft(top.removePrefix("draft:").toLong())
                    else -> EditTarget.Existing(top.removePrefix("edit:").toLong())
                }
                // The editor handles Back itself (draft guard).
                androidx.compose.runtime.key(top) {
                    RecipeEditorScreen(
                        target = target,
                        onClose = { pop() },
                        onSaved = { id ->
                            // After saving, show the recipe in place of the editor (and drop an
                            // older copy of the same recipe screen underneath, if any).
                            val below = stack.dropLast(1).filter { it != "recipe:$id" }
                            stackText = (below + "recipe:$id").joinToString("|")
                            dest = Dest.Recipes
                        },
                    )
                }
            }
            else -> {
                // Unknown route (should not happen): drop it.
                androidx.compose.runtime.LaunchedEffect(top) { pop() }
            }
        }
        if (showAdd) {
            AddSheet(
                onDismiss = { showAdd = false },
                onNewRecipe = {
                    showAdd = false
                    dest = Dest.Recipes
                    push("edit:new")
                },
                onListItem = {
                    showAdd = false
                    showAddItem = true
                },
            )
        }
        if (showAddItem) {
            AddItemDialog(weekStart, onDone = { showAddItem = false; if (stack.isEmpty()) dest = Dest.List })
        }
        if (splash) OpeningScreen(onDone = { splash = false })
    }
}
