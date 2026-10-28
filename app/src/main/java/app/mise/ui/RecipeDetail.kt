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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mise.data.Choices
import app.mise.data.Ingredient
import app.mise.data.Meal
import app.mise.data.Prefs
import app.mise.data.Recipe
import app.mise.data.RecipeRepo
import app.mise.data.RecipeStatus
import app.mise.data.Scaling
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * A recipe, scaled. Opened from the plan it starts at the planned servings and swaps. The
 * footer adds it to the plan or starts cook mode with the same servings and swaps.
 */
@Composable
fun RecipeDetailScreen(
    id: Long,
    initialServings: Int?,
    initialChoices: List<String>?,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onCook: (Int, List<String>) -> Unit,
) {
    val repo = RecipeRepo.get(LocalContext.current)
    // Unit while loading, null when deleted, the recipe otherwise.
    val state by remember(id) { repo.observe(id) }.collectAsState(initial = Unit)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(MiseIcons.Back, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (state is Recipe) IconTile(MiseIcons.Edit, "Edit recipe") { onEdit(id) }
        }
        when (val r = state) {
            is Recipe -> RecipeBody(r, initialServings, initialChoices, onCook)
            null -> Column(Modifier.padding(16.dp)) { EmptyState("Recipe not found", "It may have been deleted, here or on the other phone.") }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeBody(r: Recipe, initialServings: Int?, initialChoices: List<String>?, onCook: (Int, List<String>) -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    var servings by rememberSaveable(r.id) { mutableIntStateOf(initialServings ?: Prefs.defaultServings.takeIf { it > 0 } ?: r.baseServings) }
    var choiceText by rememberSaveable(r.id) { mutableStateOf(Choices.encode(initialChoices ?: Choices.decode("", r))) }
    val choices = Choices.decode(choiceText, r)
    var addToPlan by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            val eyebrow = listOf(r.cuisine, r.dishType).filter { it.isNotBlank() }.joinToString(" · ")
            if (eyebrow.isNotEmpty()) Text(eyebrow.uppercase(), style = Mise.type.labelS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            Text(r.title, style = Mise.type.displayM, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            val times = listOfNotNull(r.prepMins?.let { "$it min prep" }, r.cookMins?.let { "$it min cook" })
            if (times.isNotEmpty()) Text(times.joinToString("   ").uppercase(), style = Mise.type.label, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            if (r.tags.isNotEmpty()) Text(r.tags.joinToString(" · "), style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

            if (r.status == RecipeStatus.TO_CHECK) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("To check", style = Mise.type.bodyM, color = c.text)
                        Text(
                            "Imported, with some amounts estimated (marked est.). Check them once you have cooked it. Editing, or Looks right, marks it reviewed so a re-import never replaces it.",
                            style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        GhostButton("Looks right", {
                            repo.scope.launch { repo.markChecked(r.id) }
                            Toasts.show("Marked as checked")
                        })
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Servings", style = Mise.type.bodyM, color = c.text)
                        val note = when {
                            servings == r.baseServings -> "As written"
                            r.exact -> "Recipe is for ${r.baseServings} · exact scaling"
                            else -> "Recipe is for ${r.baseServings}"
                        }
                        Text(note, style = Mise.type.bodyS, color = c.text2)
                    }
                    Stepper(servings, { servings = it }, label = "servings")
                }
            }

            r.slots.forEachIndexed { si, slot ->
                SectionLabel(slot.name, "swap")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slot.options.forEach { o ->
                        MiseChip(o.label, choices.getOrNull(si) == o.label) {
                            choiceText = Choices.encode(choices.mapIndexed { k, v -> if (k == si) o.label else v })
                        }
                    }
                }
            }

            val chosen = Choices.options(r, choices)
            val swapped = chosen.flatMap { it.ingredients }
            SectionLabel("Ingredients", (r.ingredients.size + swapped.size).toString())
            Card {
                val rows = swapped.map { it to true } + r.ingredients.map { it to false }
                if (rows.isEmpty()) Text("No ingredients yet", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(16.dp))
                rows.forEachIndexed { i, (ing, isSwap) ->
                    if (i > 0) Divider()
                    IngredientLine(ing, isSwap, r, servings)
                }
            }
            chosen.filter { it.note.isNotBlank() }.forEach {
                Text("${it.label}: ${it.note}", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }

            if (r.steps.isNotEmpty()) {
                SectionLabel("Steps", r.steps.size.toString())
                Card {
                    r.steps.forEachIndexed { i, step ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("${i + 1}".padStart(2, '0'), style = Mise.type.label, color = c.text2, modifier = Modifier.width(32.dp).padding(top = 3.dp))
                            Text(step, style = Mise.type.body, color = c.text, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (r.notes.isNotBlank()) {
                SectionLabel("Notes")
                Card { Text(r.notes, style = Mise.type.body, color = c.text, modifier = Modifier.padding(16.dp)) }
            }
        }

        Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.heightIn(min = 52.dp).background(c.surface, PillShape)
                        .border(BorderStroke(1.dp, c.line), PillShape)
                        .clickable(role = Role.Button, onClickLabel = "Start cooking") { onCook(servings, choices) }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MiseIcons.Cook, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cook", style = Mise.type.bodyM, color = c.text)
                }
                PrimaryButton("Add to plan", { addToPlan = true }, Modifier.weight(1f))
            }
        }
    }

    if (addToPlan) {
        val today = LocalDate.now()
        PlanEntrySheet(
            mode = PlanSheet.Add(today, Meal.DINNER, recipeId = r.id, servings = servings, choices = choices),
            days = (0..13).map { today.plusDays(it.toLong()) },
            onDismiss = { addToPlan = false },
            onGoToRecipes = { addToPlan = false },
            keepOpen = false,
        )
    }
}

@Composable
private fun IngredientLine(ing: Ingredient, isSwap: Boolean, r: Recipe, servings: Int) {
    val c = Mise.colours
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (isSwap) Modifier.background(c.soft) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ing.name, style = Mise.type.body, color = c.text)
            if (ing.note.isNotBlank()) Text(ing.note, style = Mise.type.bodyS, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            Scaling.scaledText(ing.qty, ing.unit, r.baseServings, servings, ing.name, r.exact),
            style = Mise.type.number, color = c.text, textAlign = TextAlign.End,
        )
    }
}
