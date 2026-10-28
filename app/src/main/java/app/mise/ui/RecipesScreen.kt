package app.mise.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.RecipeRepo
import app.mise.data.RecipeSummary
import app.mise.data.Tags

/**
 * The library: search (titles, cuisines and ingredients), then dish type and cuisine filters
 * with counts, and a To check filter for imported recipes not yet reviewed.
 */
@Composable
fun RecipesScreen(onSettings: () -> Unit, onOpen: (Long) -> Unit, onOpenDraft: (Long) -> Unit, onNew: () -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    val recipes by remember { repo.summaries() }.collectAsState(initial = null)
    val drafts by remember { repo.drafts() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var cuisine by rememberSaveable { mutableStateOf("") }
    var toCheck by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        val all = recipes
        ScreenHeader(
            eyebrow = if (all == null) "Your library" else if (all.size == 1) "1 recipe" else "${all.size} recipes",
            title = "Recipes",
            actionIcon = MiseIcons.Settings, actionLabel = "Settings", onAction = onSettings,
        )
        if (all == null) return@Column
        val q = query.trim().lowercase()
        val searched = if (q.isEmpty()) all else all.filter { it.searchText.contains(q) }
        fun matches(r: RecipeSummary, t: String = type, cu: String = cuisine, chk: Boolean = toCheck) =
            (t.isEmpty() || r.dishType == t) && (cu.isEmpty() || r.cuisine == cu) && (!chk || r.toCheck)
        val shown = searched.filter { matches(it) }
        val checkCount = searched.count { matches(it, chk = false) && it.toCheck }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                MiseField(
                    query, { query = it }, "Search dishes or ingredients",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp), imeAction = ImeAction.Search,
                )
            }
            if (all.isNotEmpty()) {
                item(key = "types") {
                    Spacer(Modifier.height(10.dp))
                    ChipRow {
                        MiseChip("All", type.isEmpty() && !toCheck, count = searched.count { matches(it, t = "", chk = false) }) { type = ""; toCheck = false }
                        if (checkCount > 0) MiseChip("To check", toCheck, count = checkCount) { toCheck = !toCheck }
                        Tags.dishTypes.forEach { t ->
                            val n = searched.count { matches(it, t = t) }
                            if (n > 0 || type == t) MiseChip(t, type == t, count = n) { type = if (type == t) "" else t }
                        }
                    }
                }
                item(key = "cuisines") {
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        Tags.cuisines.forEach { cu ->
                            val n = searched.count { matches(it, cu = cu) }
                            if (n > 0 || cuisine == cu) MiseChip(cu, cuisine == cu, count = n) { cuisine = if (cuisine == cu) "" else cu }
                        }
                    }
                }
            }
            if (drafts.isNotEmpty() && q.isEmpty()) {
                item(key = "draftsLabel") { SectionLabel("Drafts", drafts.size.toString(), Modifier.padding(horizontal = 16.dp)) }
                item(key = "drafts") {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        drafts.forEachIndexed { i, d ->
                            if (i > 0) Divider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpenDraft(d.id) }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(d.title, style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Pill(if (d.recipeId != null) "Editing" else "Unsaved")
                            }
                        }
                    }
                }
            }
            val filtered = type.isNotEmpty() || cuisine.isNotEmpty() || toCheck
            item(key = "label") {
                SectionLabel(
                    if (filtered || q.isNotEmpty()) "Matching" else "All recipes",
                    shown.size.toString(),
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            when {
                all.isEmpty() -> item(key = "empty") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        EmptyState("No recipes yet", "Write your first recipe with servings and swaps, or import your Folio recipes in Settings.")
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Write a recipe", onNew, Modifier.fillMaxWidth())
                    }
                }
                shown.isEmpty() -> item(key = "none") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        EmptyState("Nothing matches", if (q.isNotEmpty()) "No dish or ingredient contains “${query.trim()}” with these filters." else "No recipe has these filters yet.")
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Clear filters", { type = ""; cuisine = ""; toCheck = false; query = "" }, Modifier.fillMaxWidth())
                    }
                }
                else -> itemsIndexed(shown, key = { _, r -> r.id }) { i, r ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        RecipeRow(r, first = i == 0, last = i == shown.lastIndex) { onOpen(r.id) }
                    }
                }
            }
        }
    }
}

/** One recipe in the list. Rows join into one card: rounded top on the first, bottom on the last. */
@Composable
private fun RecipeRow(r: RecipeSummary, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Mise.colours
    Column(Modifier.fillMaxWidth().joinedCell(first, last, c.surface, c.line)) {
        if (!first) Divider(Modifier.padding(start = 68.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(r.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = Mise.type.bodyM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    if (r.dishType.isNotBlank()) add(r.dishType)
                    if (r.cuisine.isNotBlank()) add(r.cuisine)
                    if (r.slotCount > 0) add(if (r.slotCount == 1) "1 swap" else "${r.slotCount} swaps")
                    agoText(r.lastPlanned)?.let { add("had $it") }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = Mise.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.toCheck) {
                Spacer(Modifier.width(8.dp))
                Pill("To check")
            }
        }
    }
}
