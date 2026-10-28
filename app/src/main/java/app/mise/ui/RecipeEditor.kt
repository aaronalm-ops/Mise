package app.mise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.mise.data.EIng
import app.mise.data.EOption
import app.mise.data.ESlot
import app.mise.data.EStep
import app.mise.data.EditorState
import app.mise.data.Measure
import app.mise.data.RecipeRepo
import app.mise.data.RecipeStatus
import app.mise.data.Scaling
import app.mise.data.Tags
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Where the editor starts: a brand new recipe, an existing recipe, or a saved draft. */
sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val recipeId: Long) : EditTarget
    data class Draft(val draftId: Long) : EditTarget
}

/**
 * Full-screen recipe editor. Every change is kept as a draft within a second, and the screen
 * route then points at that draft, so rotation, a theme switch or the phone closing the app
 * mid-way lose nothing. Close with changes asks Keep draft / Discard. Saving marks the recipe
 * reviewed (no longer To check).
 */
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(
    target: EditTarget,
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    onDraftCreated: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    val names by remember { repo.ingredientNames() }.collectAsState(initial = emptyList())
    var state by remember { mutableStateOf<EditorState?>(null) }
    var draftId by remember { mutableStateOf<Long?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var draftSaved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf<PasteKind?>(null) }
    var focusKey by remember { mutableStateOf<Long?>(null) }
    val confirm = Haptic.rememberConfirm()

    LaunchedEffect(Unit) {
        when (target) {
            EditTarget.New -> state = EditorState()
            is EditTarget.Existing -> {
                val resumed = repo.draftForRecipe(target.recipeId)
                if (resumed != null) {
                    draftId = resumed.first; state = resumed.second; draftSaved = true
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

    // Autosave: shortly after typing stops, write the draft (never after Save, Discard or Delete).
    LaunchedEffect(Unit) {
        snapshotFlow { state }
            .drop(1)
            .debounce(700)
            .collect { s ->
                if (s != null && dirty && !saving && !closing && s.hasContent) {
                    val first = draftId == null
                    val id = repo.saveDraft(draftId, s)
                    if (closing) { repo.deleteDraft(id); return@collect }
                    draftId = id; draftSaved = true
                    if (first && target is EditTarget.New) onDraftCreated(id)
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
        if (dirty && state?.hasContent == true) askDiscard = true else onClose()
    }

    fun save() {
        val s = state ?: return
        if (s.title.isBlank()) { error = "Add a name first."; return }
        saving = true; closing = true; error = null
        repo.scope.launch {
            val result = runCatching { repo.save(s.copy(status = RecipeStatus.OK)) }
            result.onSuccess { id ->
                draftId?.let { repo.deleteDraft(it) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    saving = false; dirty = false
                    confirm()
                    onSaved(id)
                }
            }.onFailure {
                draftId = repo.saveDraft(draftId, s)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    saving = false; closing = false
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
            IconTile(MiseIcons.Close, "Close") { requestClose() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s?.recipeId == null) "New recipe" else "Edit recipe", style = Mise.type.displayS, color = c.text)
                Text(
                    when {
                        saving -> "SAVING"
                        draftSaved -> "DRAFT KEPT"
                        dirty -> "EDITING"
                        else -> " "
                    },
                    style = Mise.type.labelS, color = c.text2,
                )
            }
        }
        if (s == null) return@Column
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            MiseField(s.title, { v -> update { it.copy(title = v) } }, "Recipe name", Modifier.fillMaxWidth(), big = true, capitalization = KeyboardCapitalization.Words, minHeight = 56, error = error != null && s.title.isBlank())

            SectionLabel("Dish type")
            TagChips(Tags.dishTypes, s.dishType) { v -> update { it.copy(dishType = v) } }
            SectionLabel("Cuisine")
            TagChips(Tags.cuisines, s.cuisine) { v -> update { it.copy(cuisine = v) } }

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
            Spacer(Modifier.height(8.dp))
            MiseField(s.tags, { v -> update { it.copy(tags = v) } }, "Tags, e.g. Vegetarian, Quick", Modifier.fillMaxWidth())

            SectionLabel("Ingredients", "always used")
            IngredientList(
                list = s.ings, names = names, focusKey = focusKey, onFocused = { focusKey = null },
                onChange = { list -> update { it.copy(ings = list) } },
                onAdd = { list, k -> focusKey = k; update { it.copy(ings = list) } },
            )
            Row {
                AddRow("Add ingredient", Modifier.weight(1f)) {
                    val n = EIng(); focusKey = n.key; update { it.copy(ings = it.ings + n) }
                }
                TextAction("Paste a list", { paste = PasteKind.Ingredients })
            }

            SectionLabel("Swaps", "e.g. protein")
            Text(
                "A swap is a choice made when you plan it: chicken, mutton or prawn. Each option has its own ingredients.",
                style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            s.slots.forEachIndexed { si, slot ->
                key(slot.key) {
                    SlotEditor(
                        slot = slot, names = names, focusKey = focusKey, onFocused = { focusKey = null },
                        onFocusNew = { focusKey = it },
                        onChange = { ns -> update { st -> st.copy(slots = st.slots.map { if (it.key == slot.key) ns else it }) } },
                        onRemove = { update { st -> st.copy(slots = st.slots.filter { it.key != slot.key }) } },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            AddRow("Add a swap") { update { it.copy(slots = it.slots + ESlot()) } }

            SectionLabel("Steps")
            Reorderable(
                items = s.steps, keyOf = { it.key },
                onMove = { from, to -> update { st -> st.copy(steps = st.steps.move(from, to)) } },
            ) { i, step, handle ->
                Row(Modifier.fillMaxWidth().background(c.surface, CardShape).border(BorderStroke(1.dp, c.line), CardShape).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
                    Box(handle.size(40.dp, 48.dp), contentAlignment = Alignment.Center) {
                        Text("${i + 1}".padStart(2, '0'), style = Mise.type.label, color = c.text2)
                    }
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(focusKey) { if (focusKey == step.key) { delay(60); runCatching { fr.requestFocus() }; focusKey = null } }
                    MiseField(
                        step.text,
                        { v -> update { st -> st.copy(steps = st.steps.map { if (it.key == step.key) it.copy(text = v) else it }) } },
                        "Describe step ${i + 1}", Modifier.weight(1f), singleLine = false, focusRequester = fr,
                    )
                    RemoveButton("Remove step ${i + 1}") {
                        update { st -> st.copy(steps = st.steps.filter { it.key != step.key }.ifEmpty { listOf(EStep()) }) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row {
                AddRow("Add a step", Modifier.weight(1f)) { val n = EStep(); focusKey = n.key; update { it.copy(steps = it.steps + n) } }
                TextAction("Paste steps", { paste = PasteKind.Steps })
            }

            SectionLabel("Notes")
            MiseField(s.notes, { v -> update { it.copy(notes = v) } }, "Tips, where it came from, what to serve with", Modifier.fillMaxWidth(), singleLine = false, minHeight = 96)

            if (s.recipeId != null) {
                Spacer(Modifier.height(28.dp))
                GhostButton("Delete recipe", { askDelete = true }, Modifier.fillMaxWidth(), danger = true)
            }
        }

        Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
            Divider()
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) }
            PrimaryButton(
                text = if (saving) "Saving" else if (s.title.isBlank()) "Add a name to save" else "Save recipe",
                onClick = ::save,
                enabled = !saving && s.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            containerColor = c.surface,
            title = { Text("Leave this recipe?", style = Mise.type.displayS, color = c.text) },
            text = { Text("Keep it as a draft to finish later (it waits in Recipes, under Drafts), or discard what you changed.", style = Mise.type.body, color = c.text2) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    closing = true
                    val st = state
                    if (st != null) repo.scope.launch { repo.saveDraft(draftId, st) }
                    onClose()
                }) { Text("Keep draft", style = Mise.type.bodyM, color = c.text) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDiscard = false
                    closing = true
                    draftId?.let { id -> repo.scope.launch { repo.deleteDraft(id) } }
                    onClose()
                }) { Text("Discard", style = Mise.type.bodyM, color = c.danger) }
            },
        )
    }

    if (askDelete) {
        val st = state
        ConfirmDialog(
            title = "Delete ${st?.title?.ifBlank { "this recipe" } ?: "this recipe"}?",
            body = "This removes the recipe and takes it off the plan, on both phones. It cannot be undone.",
            confirm = "Delete", dismiss = "Keep", danger = true,
            onConfirm = {
                askDelete = false
                val id = st?.recipeId ?: return@ConfirmDialog
                closing = true
                repo.scope.launch {
                    draftId?.let { repo.deleteDraft(it) }
                    repo.delete(id)
                }
                Toasts.show("Recipe deleted")
                onDeleted(id)
            },
            onDismiss = { askDelete = false },
        )
    }

    paste?.let { kind ->
        PasteDialog(kind, onDismiss = { paste = null }) { text ->
            paste = null
            when (kind) {
                PasteKind.Ingredients -> {
                    val parsed = text.lines().mapNotNull { Scaling.parseLine(it) }.map { p -> EIng(name = p.name, qty = p.qty, unit = p.unit.key, note = p.note, showNote = p.note.isNotBlank()) }
                    if (parsed.isNotEmpty()) update { it.copy(ings = it.ings.filter { i -> !i.isBlank } + parsed) }
                    Toasts.show(if (parsed.size == 1) "Added 1 ingredient" else "Added ${parsed.size} ingredients")
                }
                PasteKind.Steps -> {
                    val steps = text.split(Regex("\\n\\s*\\n|\\n(?=\\s*(\\d+[.)]|step \\d+))", RegexOption.IGNORE_CASE))
                        .map { it.trim().replace(Regex("^(step\\s*)?\\d+[.):]?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*\\n\\s*"), " ") }
                        .filter { it.isNotBlank() }
                        .map { EStep(text = it) }
                    if (steps.isNotEmpty()) update { it.copy(steps = it.steps.filter { st -> st.text.isNotBlank() } + steps) }
                    Toasts.show(if (steps.size == 1) "Added 1 step" else "Added ${steps.size} steps")
                }
            }
        }
    }
}

private enum class PasteKind { Ingredients, Steps }

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val m = toMutableList()
    val x = m.removeAt(from)
    m.add(to, x)
    return m
}

@Composable
private fun PasteDialog(kind: PasteKind, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val c = Mise.colours
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (kind == PasteKind.Ingredients) "Paste ingredients" else "Paste steps", style = Mise.type.displayS, color = c.text) },
        text = {
            Column {
                Text(
                    if (kind == PasteKind.Ingredients) "One per line, like “500 g basmati rice, washed” or “2 onions”." else "One step per line or paragraph. Numbers are removed.",
                    style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(bottom = 8.dp),
                )
                MiseField(text, { text = it }, "Paste here", Modifier.fillMaxWidth(), singleLine = false, minHeight = 160)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Add", style = Mise.type.bodyM, color = if (text.isBlank()) c.text2 else c.text) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Mise.type.bodyM, color = c.text) } },
    )
}

/** One choice from a fixed list; tap the chosen one again to clear it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(options: List<String>, value: String, onPick: (String) -> Unit) {
    val all = if (value.isNotBlank() && value !in options) listOf(value) + options else options
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { o -> MiseChip(o, o == value) { onPick(if (o == value) "" else o) } }
    }
}

/**
 * A column whose rows can be dragged by their handle. Rows keep their identity by key, so a
 * row being typed in never loses focus when another moves.
 */
@Composable
private fun <T> Reorderable(
    items: List<T>,
    keyOf: (T) -> Long,
    onMove: (Int, Int) -> Unit,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    val heights = remember { mutableStateMapOf<Long, Int>() }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(items)
    val currentMove by rememberUpdatedState(onMove)
    val lift = Haptic.rememberLongPress()
    Column {
        items.forEachIndexed { i, item ->
            val k = keyOf(item)
            key(k) {
                val isDragging = dragging == k
                val handle = Modifier.pointerInput(k) {
                    detectDragGestures(
                        onDragStart = { dragging = k; offset = 0f; lift() },
                        onDragEnd = { dragging = null; offset = 0f },
                        onDragCancel = { dragging = null; offset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val list = currentItems
                            val idx = list.indexOfFirst { keyOf(it) == k }
                            if (idx < 0) return@detectDragGestures
                            if (offset > 0 && idx < list.lastIndex) {
                                val h = heights[keyOf(list[idx + 1])] ?: return@detectDragGestures
                                if (offset > h / 2f) { currentMove(idx, idx + 1); offset -= h }
                            } else if (offset < 0 && idx > 0) {
                                val h = heights[keyOf(list[idx - 1])] ?: return@detectDragGestures
                                if (-offset > h / 2f) { currentMove(idx, idx - 1); offset += h }
                            }
                        },
                    )
                }
                Column(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) offset else 0f
                            shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                            shape = CardShape
                        },
                ) { row(i, item, handle) }
            }
        }
    }
}

@Composable
private fun IngredientList(
    list: List<EIng>,
    names: List<String>,
    focusKey: Long?,
    onFocused: () -> Unit,
    onChange: (List<EIng>) -> Unit,
    onAdd: (List<EIng>, Long) -> Unit,
) {
    val c = Mise.colours
    if (list.isEmpty()) {
        Text("None yet", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        return
    }
    Reorderable(items = list, keyOf = { it.key }, onMove = { from, to -> onChange(list.move(from, to)) }) { i, ing, handle ->
        IngredientRow(
            ing = ing, handle = handle, names = names,
            requestFocus = focusKey == ing.key, onFocused = onFocused,
            onChange = { n -> onChange(list.map { if (it.key == ing.key) n else it }) },
            onRemove = { onChange(list.filter { it.key != ing.key }) },
            onNext = if (i == list.lastIndex) ({ val n = EIng(); onAdd(list + n, n.key) }) else null,
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** One line: amount, unit, name (with suggestions from your other recipes), then a menu. */
@Composable
private fun IngredientRow(
    ing: EIng,
    handle: Modifier,
    names: List<String>,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onChange: (EIng) -> Unit,
    onRemove: () -> Unit,
    onNext: (() -> Unit)?,
) {
    val c = Mise.colours
    val qtyFocus = remember { FocusRequester() }
    var nameFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) {
        if (requestFocus) { delay(60); runCatching { qtyFocus.requestFocus() }; onFocused() }
    }
    val unreadable = ing.qty.isNotBlank() && !Scaling.isReadable(ing.qty)
    val needsUnit = ing.qty.isNotBlank() && !unreadable && Measure.from(ing.unit) == Measure.NONE
    Column(Modifier.fillMaxWidth().background(c.surface, CardShape).border(BorderStroke(1.dp, c.line), CardShape).padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(28.dp, 48.dp), contentAlignment = Alignment.Center) {
                Icon(MiseIcons.Handle, contentDescription = "Drag to reorder", tint = c.text2, modifier = Modifier.size(16.dp))
            }
            MiseField(ing.qty, { onChange(ing.copy(qty = it.take(10))) }, "Qty", Modifier.width(60.dp), keyboardType = KeyboardType.Decimal, focusRequester = qtyFocus, error = unreadable, textAlign = TextAlign.Center, compact = true)
            Spacer(Modifier.width(6.dp))
            UnitPicker(ing.unit, { onChange(ing.copy(unit = it)) }, Modifier.width(68.dp), error = needsUnit)
            Spacer(Modifier.width(6.dp))
            MiseField(
                ing.name, { onChange(ing.copy(name = it)) }, "Ingredient", Modifier.weight(1f),
                imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done,
                onIme = onNext, onFocus = { nameFocused = it }, compact = true,
            )
            Box {
                Box(Modifier.size(40.dp, 48.dp).clickable(role = Role.Button, onClickLabel = "More for ${ing.name.ifBlank { "ingredient" }}") { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(MiseIcons.More, contentDescription = "More", tint = c.text2, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    DropdownMenuItem(text = { Text(if (ing.showNote) "Hide note" else "Add a note", style = Mise.type.body, color = c.text) }, onClick = { menu = false; onChange(ing.copy(showNote = !ing.showNote)) })
                    DropdownMenuItem(text = { Text("To taste", style = Mise.type.body, color = c.text) }, onClick = { menu = false; onChange(ing.copy(qty = "", unit = Measure.TO_TASTE.key)) })
                    DropdownMenuItem(text = { Text("Remove", style = Mise.type.body, color = c.danger) }, onClick = { menu = false; onRemove() })
                }
            }
        }
        if (nameFocused && ing.name.trim().length >= 2) {
            val q = ing.name.trim().lowercase()
            val hits = names.filter { it.lowercase().contains(q) && !it.equals(ing.name.trim(), true) }.sortedBy { if (it.lowercase().startsWith(q)) 0 else 1 }.take(4)
            if (hits.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 30.dp, end = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hits.forEach { h -> MiseChip(h, false) { onChange(ing.copy(name = h)) } }
                }
            }
        }
        if (ing.showNote || ing.note.isNotBlank()) {
            MiseField(ing.note, { onChange(ing.copy(note = it)) }, "Note, e.g. finely chopped", Modifier.fillMaxWidth().padding(start = 30.dp, end = 8.dp, top = 6.dp), capitalization = KeyboardCapitalization.None, compact = true)
        }
        if (unreadable) {
            Text("Mise cannot read “${ing.qty.trim()}” as an amount. It will be kept in the note.", style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 30.dp, end = 8.dp, top = 6.dp))
        } else if (needsUnit) {
            Text("Choose a unit (Mise will guess one if you do not).", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 30.dp, end = 8.dp, top = 6.dp))
        }
    }
}

/**
 * A swap, flat: its name, the options as chips (tap to edit one), and the chosen option's
 * ingredients and note below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(
    slot: ESlot,
    names: List<String>,
    focusKey: Long?,
    onFocused: () -> Unit,
    onFocusNew: (Long) -> Unit,
    onChange: (ESlot) -> Unit,
    onRemove: () -> Unit,
) {
    val c = Mise.colours
    var selectedKey by remember { mutableStateOf(slot.options.firstOrNull()?.key) }
    val selected = slot.options.firstOrNull { it.key == selectedKey } ?: slot.options.firstOrNull()
    Column(Modifier.fillMaxWidth().background(c.soft, CardShape).border(BorderStroke(1.dp, c.line), CardShape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiseField(slot.name, { onChange(slot.copy(name = it)) }, "Swap name, e.g. Protein", Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            RemoveButton("Remove swap ${slot.name}", onRemove)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slot.options.forEachIndexed { oi, o ->
                MiseChip(o.label.ifBlank { "Option ${oi + 1}" } + if (o.isDefault) " ·" else "", o.key == selected?.key) { selectedKey = o.key }
            }
            MiseChip("+ Option", false) {
                val n = EOption(isDefault = slot.options.isEmpty())
                onChange(slot.copy(options = slot.options + n))
                selectedKey = n.key
            }
        }
        val o = selected ?: return@Column
        fun set(n: EOption) = onChange(slot.copy(options = slot.options.map { if (it.key == o.key) n else it }))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiseField(o.label, { set(o.copy(label = it)) }, "Option, e.g. Chicken", Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            RemoveButton("Remove option ${o.label}") {
                val rest = slot.options.filter { it.key != o.key }
                val fixed = if (rest.isNotEmpty() && rest.none { it.isDefault }) rest.mapIndexed { k, x -> x.copy(isDefault = k == 0) } else rest
                onChange(slot.copy(options = fixed))
                selectedKey = fixed.firstOrNull()?.key
            }
        }
        Row(
            Modifier.padding(top = 2.dp).clickable(role = Role.RadioButton) { onChange(slot.copy(options = slot.options.map { it.copy(isDefault = it.key == o.key) })) }.padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).border(BorderStroke(1.5.dp, c.text), androidx.compose.foundation.shape.CircleShape).padding(4.dp)
                    .then(if (o.isDefault) Modifier.background(c.text, androidx.compose.foundation.shape.CircleShape) else Modifier),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (o.isDefault) "Default choice" else "Make this the default", style = Mise.type.bodyS, color = c.text)
        }
        IngredientList(
            list = o.ings, names = names, focusKey = focusKey, onFocused = onFocused,
            onChange = { set(o.copy(ings = it)) },
            onAdd = { list, k -> onFocusNew(k); set(o.copy(ings = list)) },
        )
        AddRow("Add ingredient for ${o.label.ifBlank { "this option" }}") {
            val n = EIng(); onFocusNew(n.key); set(o.copy(ings = o.ings + n))
        }
        MiseField(o.note, { set(o.copy(note = it)) }, "Cooking note for this option (optional)", Modifier.fillMaxWidth())
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
