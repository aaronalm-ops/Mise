package app.mise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.RecipeRepo
import app.mise.data.RecipeSummary

@Composable
fun RecipesScreen(onSettings: () -> Unit, onOpen: (Long) -> Unit, onOpenDraft: (Long) -> Unit, onNew: () -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    val recipes by remember { repo.summaries() }.collectAsState(initial = null)
    val drafts by remember { repo.drafts() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Your library", "Recipes", MiseIcons.Settings, "Settings", onSettings)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiseField(query, { query = it }, "Search recipes", Modifier.weight(1f))
            }
            if (drafts.isNotEmpty()) {
                SectionLabel("Drafts", drafts.size.toString())
                Card {
                    drafts.forEachIndexed { i, d ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpenDraft(d.id) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(d.title, style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (d.recipeId != null) "EDITING" else "UNSAVED", style = Mise.type.labelS, color = c.text2)
                        }
                    }
                }
            }
            val list = recipes
            val shown = list?.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.cuisine.contains(query.trim(), ignoreCase = true) }
            SectionLabel("All recipes", (list?.size ?: 0).toString())
            when {
                list == null -> Unit
                list.isEmpty() -> {
                    EmptyState("No recipes yet", "Write your first recipe with servings and swaps. You can also use Add in the bar below.")
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Write a recipe", onNew, Modifier.fillMaxWidth())
                }
                shown.isNullOrEmpty() -> EmptyState("Nothing matches", "No recipe title or cuisine contains \"${query.trim()}\".")
                else -> Card {
                    shown.forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        RecipeRow(r) { onOpen(r.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(r: RecipeSummary, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(r.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = Mise.type.bodyM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                add("Serves ${r.baseServings}")
                if (r.slotCount > 0) add(if (r.slotCount == 1) "1 swap" else "${r.slotCount} swaps")
                if (r.cuisine.isNotBlank()) add(r.cuisine)
            }.joinToString(" · ")
            Text(meta, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two-letter tile until recipes have photos. Matte graphite, never colour. */
@Composable
fun Monogram(title: String) {
    val c = Mise.colours
    val shape = RoundedCornerShape(10.dp)
    val letters = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(48.dp).background(c.disc, shape).matteGrain(shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letters, style = Mise.type.bodyM, color = c.onDisc)
    }
}
