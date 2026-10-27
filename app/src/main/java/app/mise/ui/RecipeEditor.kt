package app.mise.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mise.data.EIng
import app.mise.data.EOption
import app.mise.data.ESlot
import app.mise.data.EStep
import app.mise.data.EditorState
import app.mise.data.RecipeRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the editor starts: a brand new recipe, an existing recipe, or a saved draft.
 */
sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val recipeId: Long) : EditTarget
    data class Draft(val draftId: Long) : EditTarget
}

/**
 * Full-screen recipe editor (Panels v2 rules applied to a long form): task heading, reachable
 * Close, fixed Save footer. Every change is kept as a draft within a second, so closing the app
 * mid-way loses nothing. Close or Back with changes asks Keep editing / Discard. Save locks the
 * form, writes the recipe, removes the draft and opens the recipe. A failed save keeps the
 * draft and says so.
 */
@OptIn(FlowPreview::class)
@Composable
fun RecipeEditorScreen(target: EditTarget, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    var state by remember { mutableStateOf<EditorState?>(null) }
    var draftId by remember { mutableStateOf<Long?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var draftSaved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        when (target) {
            EditTarget.New -> state = EditorState()
            is EditTarget.Existing -> {
                // Resume an unfinished edit of this recipe if there is one.
                val existingDraft = repo.draftIdForRecipe(target.recipeId)
                val fromDraft = existingDraft?.let { repo.draft(it) }
                if (fromDraft != null) {
                    draftId = existingDraft; state = fromDraft; draftSaved = true
                } else {
                    state = repo.get(target.recipeId)?.let { EditorState.fromRecipe(it) } ?: EditorState()
                }
            }
            is EditTarget.Draft -> {
                draftId = target.draftId
                state = repo.draft(target.draftId) ?: EditorState()
                draftSaved = true
            }
        }
    }

    // Autosave: one second after typing stops, write the draft.
    LaunchedEffect(Unit) {
        snapshotFlow { state }
            .drop(1)
            .debounce(800)
            .collect { s ->
                if (s != null && dirty && !saving && s.hasContent) {
                    val id = withContext(Dispatchers.IO) { repo.saveDraft(draftId, s) }
                    draftId = id; draftSaved = true
                }
            }
    }

    fun update(f: (EditorState) -> EditorState) {
        val s = state ?: return
        if (saving) return
        state = f(s); dirty = true; draftSaved = false; error = null
    }

    fun requestClose() {
        if (saving) return
        // Unchanged since opening: just close. An existing draft stays in Drafts.
        if (dirty && state?.hasContent == true) askDiscard = true else onClose()
    }

    fun save() {
        val s = state ?: return
        if (s.title.isBlank()) { error = "Add a title first."; return }
        saving = true; error = null
        repo.scope.launch {
            val result = runCatching { repo.save(s) }
            withContext(Dispatchers.Main) {
                result.onSuccess { id ->
                    draftId?.let { d -> repo.scope.launch { repo.deleteDraft(d) } }
                    saving = false; dirty = false
                    onSaved(id)
                }.onFailure {
                    saving = false
                    // Keep the draft so nothing is lost; say what happened and how to retry.
                    repo.scope.launch { draftId = repo.saveDraft(draftId, s) }
                    error = "Could not save. Your draft is kept. Tap Save to try again."
                }
            }
        }
    }

    BackHandler { requestClose() }

    val s = state
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(MiseIcons.Close, "Close", ::requestClose)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (target is EditTarget.New || s?.recipeId == null) "New recipe" else "Edit recipe", style = Mise.type.displayS, color = c.text)
                Text(
                    when {
                        saving -> "SAVING"
                        draftSaved -> "DRAFT KEPT"
                        else -> " "
                    },
                    style = Mise.type.labelS, color = c.text2,
                )
            }
        }
        if (s == null) return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            MiseField(s.title, { v -> update { it.copy(title = v) } }, "Recipe name", Modifier.fillMaxWidth(), big = true, capitalization = KeyboardCapitalization.Words, minHeight = 56)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiseField(s.cuisine, { v -> update { it.copy(cuisine = v) } }, "Cuisine", Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
                MiseField(s.tags, { v -> update { it.copy(tags = v) } }, "Tags, with commas", Modifier.weight(1f))
            }

            SectionLabel("Serves and time")
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Serves", style = Mise.type.bodyM, color = c.text, modifier = Modifier.weight(1f))
                    Stepper(s.serves, { v -> update { it.copy(serves = v) } }, max = 99, label = "servings")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiseField(s.prep, { v -> update { it.copy(prep = v.filter(Char::isDigit).take(4)) } }, "Prep minutes", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                MiseField(s.cook, { v -> update { it.copy(cook = v.filter(Char::isDigit).take(4)) } }, "Cook minutes", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }

            SectionLabel("Ingredients", "always used")
            IngredientList(s.ings, onChange = { list -> update { it.copy(ings = list) } })

            SectionLabel("Swaps", "e.g. protein")
            Text(
                "A swap lets you choose between options, like chicken, mutton or prawn. Each option has its own ingredients.",
                style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            s.slots.forEachIndexed { si, slot ->
                androidx.compose.runtime.key(slot.key) {
                    SlotEditor(
                        slot = slot,
                        onChange = { ns -> update { st -> st.copy(slots = st.slots.toMutableList().also { it[si] = ns }) } },
                        onRemove = { update { st -> st.copy(slots = st.slots.filterIndexed { i, _ -> i != si }) } },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            AddRow("Add a swap") { update { it.copy(slots = it.slots + ESlot()) } }

            SectionLabel("Steps")
            Card {
                s.steps.forEachIndexed { i, step ->
                    androidx.compose.runtime.key(step.key) {
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Text("${i + 1}".padStart(2, '0'), style = Mise.type.label, color = c.text2, modifier = Modifier.width(28.dp).padding(top = 16.dp))
                            MiseField(
                                step.text,
                                { v -> update { st -> st.copy(steps = st.steps.toMutableList().also { it[i] = step.copy(text = v) }) } },
                                "Describe step ${i + 1}",
                                Modifier.weight(1f),
                                singleLine = false,
                            )
                            RemoveButton("Remove step ${i + 1}") {
                                update { st -> st.copy(steps = st.steps.filterIndexed { n, _ -> n != i }.ifEmpty { listOf(EStep()) }) }
                            }
                        }
                    }
                }
            }
            AddRow("Add a step") { update { it.copy(steps = it.steps + EStep()) } }

            SectionLabel("Notes")
            MiseField(s.notes, { v -> update { it.copy(notes = v) } }, "Tips, where it came from, what to serve with", Modifier.fillMaxWidth(), singleLine = false, minHeight = 96)
        }

        // Fixed footer: the one task action, always reachable.
        Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
            Divider()
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) }
            PrimaryButton(
                text = if (saving) "Saving" else "Save recipe",
                onClick = ::save,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (askDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askDiscard = false },
            containerColor = c.surface,
            title = { Text("Leave this recipe?", style = Mise.type.displayS, color = c.text) },
            text = { Text("Keep it as a draft to finish later (it waits in Recipes, under Drafts), or discard what you changed.", style = Mise.type.body, color = c.text2) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.TextButton(onClick = {
                        askDiscard = false
                        val st = state
                        if (st != null) repo.scope.launch { repo.saveDraft(draftId, st) }
                        dirty = false
                        onClose()
                    }) { Text("Keep draft", style = Mise.type.bodyM, color = c.text) }
                    androidx.compose.material3.TextButton(onClick = {
                        askDiscard = false
                        dirty = false
                        draftId?.let { id -> repo.scope.launch { repo.deleteDraft(id) } }
                        onClose()
                    }) { Text("Discard", style = Mise.type.bodyM, color = c.danger) }
                    androidx.compose.material3.TextButton(onClick = { askDiscard = false }) {
                        Text("Keep editing", style = Mise.type.bodyM, color = c.text)
                    }
                }
            },
        )
    }
}

@Composable
private fun IngredientList(list: List<EIng>, onChange: (List<EIng>) -> Unit, addLabel: String = "Add ingredient") {
    Card {
        list.forEachIndexed { i, ing ->
            androidx.compose.runtime.key(ing.key) {
                if (i > 0) Divider()
                IngredientEditor(
                    ing = ing,
                    onChange = { n -> onChange(list.toMutableList().also { it[i] = n }) },
                    onRemove = { onChange(list.filterIndexed { k, _ -> k != i }) },
                )
            }
        }
        if (list.isEmpty()) Text("None yet", style = Mise.type.bodyS, color = Mise.colours.text2, modifier = Modifier.padding(16.dp))
    }
    AddRow(addLabel) { onChange(list + EIng()) }
}

@Composable
private fun IngredientEditor(ing: EIng, onChange: (EIng) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiseField(ing.name, { onChange(ing.copy(name = it)) }, "Ingredient", Modifier.weight(1f))
            RemoveButton("Remove ${ing.name.ifBlank { "ingredient" }}", onRemove)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiseField(ing.qty, { onChange(ing.copy(qty = it.take(8))) }, "Qty", Modifier.width(84.dp), keyboardType = KeyboardType.Decimal)
            UnitPicker(ing.unit, { onChange(ing.copy(unit = it)) }, Modifier.width(96.dp))
            MiseField(ing.note, { onChange(ing.copy(note = it)) }, "Note", Modifier.weight(1f), capitalization = KeyboardCapitalization.None)
        }
    }
}

@Composable
private fun SlotEditor(slot: ESlot, onChange: (ESlot) -> Unit, onRemove: () -> Unit) {
    val c = Mise.colours
    Column(
        Modifier.fillMaxWidth().background(c.soft, CardShape).border(BorderStroke(1.dp, c.line), CardShape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiseField(slot.name, { onChange(slot.copy(name = it)) }, "Swap name, e.g. Protein", Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            RemoveButton("Remove swap ${slot.name}", onRemove)
        }
        slot.options.forEachIndexed { oi, opt ->
            androidx.compose.runtime.key(opt.key) {
                Spacer(Modifier.height(12.dp))
                OptionEditor(
                    opt = opt,
                    onChange = { n -> onChange(slot.copy(options = slot.options.toMutableList().also { it[oi] = n })) },
                    onMakeDefault = { onChange(slot.copy(options = slot.options.mapIndexed { k, o -> o.copy(isDefault = k == oi) })) },
                    onRemove = {
                        val rest = slot.options.filterIndexed { k, _ -> k != oi }
                        val fixed = if (rest.isNotEmpty() && rest.none { it.isDefault }) rest.mapIndexed { k, o -> o.copy(isDefault = k == 0) } else rest
                        onChange(slot.copy(options = fixed))
                    },
                )
            }
        }
        AddRow("Add an option") { onChange(slot.copy(options = slot.options + EOption(isDefault = slot.options.isEmpty()))) }
    }
}

@Composable
private fun OptionEditor(opt: EOption, onChange: (EOption) -> Unit, onMakeDefault: () -> Unit, onRemove: () -> Unit) {
    val c = Mise.colours
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiseField(opt.label, { onChange(opt.copy(label = it)) }, "Option, e.g. Chicken", Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            RemoveButton("Remove option ${opt.label}", onRemove)
        }
        Row(
            Modifier.padding(top = 4.dp).clickable(role = Role.RadioButton, onClick = onMakeDefault).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).border(BorderStroke(1.5.dp, c.text), CircleShape).padding(4.dp)
                    .then(if (opt.isDefault) Modifier.background(c.text, CircleShape) else Modifier),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (opt.isDefault) "Default choice" else "Make default", style = Mise.type.bodyS, color = c.text)
        }
        IngredientList(opt.ings, onChange = { onChange(opt.copy(ings = it)) }, addLabel = "Add ingredient for ${opt.label.ifBlank { "this option" }}")
        Spacer(Modifier.height(6.dp))
        MiseField(opt.note, { onChange(opt.copy(note = it)) }, "Cooking note for this option (optional)", Modifier.fillMaxWidth())
    }
}

@Composable
private fun RemoveButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MiseIcons.Close, contentDescription = label, tint = Mise.colours.text2, modifier = Modifier.size(18.dp))
    }
}
