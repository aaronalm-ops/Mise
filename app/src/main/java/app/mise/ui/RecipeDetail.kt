package app.mise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import app.mise.data.Ingredient
import app.mise.data.Recipe
import app.mise.data.RecipeRepo
import app.mise.data.Scaling
import kotlinx.coroutines.launch

@Composable
fun RecipeDetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val repo = RecipeRepo.get(LocalContext.current)
    // Wrapped so "still loading" (null outer) differs from "deleted" (null inside).
    val state by remember(id) { repo.observe(id) }.collectAsState(initial = Unit)
    val c = Mise.colours
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(MiseIcons.Back, "Back", onBack)
            Spacer(Modifier.weight(1f))
            if (state is Recipe) IconTile(MiseIcons.Edit, "Edit recipe") { onEdit(id) }
        }
        when (val r = state) {
            is Recipe -> RecipeBody(r, onDeleted = onBack)
            null -> Column(Modifier.padding(16.dp)) { EmptyState("Recipe not found", "It may have been deleted.") }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeBody(r: Recipe, onDeleted: () -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    var servings by rememberSaveable(r.id) { mutableIntStateOf(r.baseServings) }
    // Chosen option index per swap; defaults to each swap's default option.
    var choices by rememberSaveable(r.id, r.slots.size) {
        mutableStateOf(r.slots.map { s -> s.options.indexOfFirst { it.isDefault }.coerceAtLeast(0) })
    }
    var askDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        val eyebrow = (listOf(r.cuisine) + r.tags).filter { it.isNotBlank() }.joinToString(" · ")
        if (eyebrow.isNotEmpty()) Text(eyebrow.uppercase(), style = Mise.type.labelS, color = c.text2, modifier = Modifier.padding(top = 8.dp))
        Text(r.title, style = Mise.type.displayM, color = c.text, modifier = Modifier.padding(top = 6.dp))
        val times = listOfNotNull(r.prepMins?.let { "$it min prep" }, r.cookMins?.let { "$it min cook" })
        if (times.isNotEmpty()) {
            Text(times.joinToString("   "), style = Mise.type.label, color = c.text2, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Servings", style = Mise.type.bodyM, color = c.text)
                    if (servings != r.baseServings) Text("Recipe is for ${r.baseServings}", style = Mise.type.bodyS, color = c.text2)
                }
                Stepper(servings, { servings = it }, label = "servings")
            }
        }

        r.slots.forEachIndexed { si, slot ->
            SectionLabel(slot.name, "swap")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slot.options.forEachIndexed { oi, o ->
                    MiseChip(o.label, choices.getOrElse(si) { 0 } == oi) {
                        choices = choices.toMutableList().also { if (si < it.size) it[si] = oi }
                    }
                }
            }
        }

        val chosen = r.slots.mapIndexedNotNull { si, s -> s.options.getOrNull(choices.getOrElse(si) { 0 }) }
        val swapped = chosen.flatMap { it.ingredients }
        SectionLabel("Ingredients", (r.ingredients.size + swapped.size).toString())
        Card {
            val rows = swapped.map { it to true } + r.ingredients.map { it to false }
            if (rows.isEmpty()) {
                Text("No ingredients yet", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(16.dp))
            }
            rows.forEachIndexed { i, (ing, isSwap) ->
                if (i > 0) Divider()
                IngredientLine(ing, isSwap, r.baseServings, servings)
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
        Spacer(Modifier.height(24.dp))
        GhostButton("Delete recipe", { askDelete = true }, Modifier.fillMaxWidth(), danger = true)
    }

    if (askDelete) {
        ConfirmDialog(
            title = "Delete ${r.title}?",
            body = "This removes the recipe from Mise. It cannot be undone.",
            confirm = "Delete",
            dismiss = "Keep",
            danger = true,
            onConfirm = {
                askDelete = false
                repo.scope.launch { repo.delete(r.id) }
                onDeleted()
            },
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun IngredientLine(ing: Ingredient, isSwap: Boolean, base: Int, servings: Int) {
    val c = Mise.colours
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (isSwap) Modifier.background(c.soft) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ing.name, style = Mise.type.body, color = c.text)
            if (ing.note.isNotBlank()) Text(ing.note, style = Mise.type.bodyS, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Text(Scaling.scaledText(ing.qty, ing.unit, base, servings), style = Mise.type.label.copy(letterSpacing = Mise.type.body.letterSpacing), color = if (isSwap) c.text else c.text2)
    }
}
