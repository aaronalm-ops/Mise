package app.mise.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All recipe reads and writes. Writes run on [scope] so leaving a screen never cancels a save. */
class RecipeRepo private constructor(private val db: MiseDb) {
    private val dao = db.recipes()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last import's outcome, kept here so it survives leaving Settings. */
    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing
    fun clearImportResult() { _importResult.value = null }

    fun summaries(): Flow<List<RecipeSummary>> = dao.observeSummaries().map { rows ->
        rows.map {
            RecipeSummary(
                id = it.id, title = it.title, cuisine = it.cuisine, dishType = it.dishType, baseServings = it.baseServings,
                slotCount = it.slotCount, ingredientCount = it.ingredientCount, toCheck = it.status == RecipeStatus.TO_CHECK,
                searchText = (it.title + " " + it.cuisine + " " + it.dishType + " " + (it.ingredientNames ?: "")).lowercase(),
                lastPlanned = it.lastPlanned,
            )
        }
    }

    fun drafts(): Flow<List<DraftSummary>> = dao.observeDrafts().map { rows ->
        rows.map { DraftSummary(it.id, it.title, it.recipeId, it.updatedAt) }
    }

    fun ingredientNames(): Flow<List<String>> = dao.observeIngredientNames()

    fun observe(id: Long): Flow<Recipe?> = dao.observeRecipe(id).map { e -> e?.let { load(it) } }

    suspend fun get(id: Long): Recipe? = withContext(Dispatchers.IO) { dao.recipe(id)?.let { load(it) } }

    private suspend fun load(e: RecipeEntity): Recipe {
        val rows = dao.ingredientRows(e.id)
        val options = dao.options(e.id)
        fun ing(r: IngredientRow) = Ingredient(r.name, r.qty, Measure.from(r.unit), r.prepNote)
        val slots = dao.slots(e.id).map { s ->
            Slot(
                name = s.name,
                options = options.filter { it.slotId == s.id }.map { o ->
                    SlotOption(o.label, o.isDefault, o.note, rows.filter { it.optionId == o.id }.map(::ing))
                },
            )
        }
        return Recipe(
            id = e.id, title = e.title, cuisine = e.cuisine, dishType = e.dishType,
            tags = e.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            baseServings = e.baseServings, prepMins = e.prepMins, cookMins = e.cookMins, notes = e.notes,
            ingredients = rows.filter { it.optionId == null }.map(::ing),
            slots = slots,
            steps = dao.steps(e.id).map { it.text },
            status = e.status, updatedAt = e.updatedAt,
        )
    }

    private suspend fun ingredientId(name: String): Long {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val key = clean.lowercase()
        dao.ingredientByKey(key)?.let { return it.id }
        val id = dao.insertIngredient(IngredientEntity(name = clean.replaceFirstChar { it.uppercase() }, nameKey = key))
        return if (id > 0) id else dao.ingredientByKey(key)!!.id
    }

    /**
     * Saves the editor as a recipe (new or replacing the old one) and returns its id. The sync
     * mark is written in the same transaction, so a sync can never overwrite a fresh edit.
     * An amount that cannot be read is kept in the ingredient's note rather than lost; a typed
     * amount with no unit gets a sensible one (pieces for onions, grams otherwise).
     */
    suspend fun save(s: EditorState, source: String = "manual", track: Boolean = true): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val base = RecipeEntity(
                title = s.title.trim(), cuisine = s.cuisine.trim(), dishType = s.dishType.trim(),
                tags = s.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("To check", true) && !it.equals("From Folio", true) }.joinToString(", "),
                baseServings = s.serves.coerceIn(1, 99),
                prepMins = s.prep.trim().toIntOrNull(), cookMins = s.cook.trim().toIntOrNull(),
                notes = s.notes.trim(), source = source, createdAt = now, updatedAt = now,
                status = s.status.ifBlank { RecipeStatus.OK },
            )
            val existing = s.recipeId?.let { dao.recipe(it) }
            val id = if (existing != null) {
                dao.updateRecipe(base.copy(id = existing.id, createdAt = existing.createdAt, source = if (track) existing.source else source))
                dao.deleteIngredients(existing.id); dao.deleteOptions(existing.id); dao.deleteSlots(existing.id); dao.deleteSteps(existing.id)
                existing.id
            } else {
                dao.insertRecipe(base)
            }
            var pos = 0
            val pending = mutableListOf<Pair<EIng, Long?>>()
            fun add(list: List<EIng>, optionId: Long?) = list.filter { it.name.isNotBlank() }.forEach { pending += it to optionId }
            add(s.ings, null)
            s.slots.filter { it.name.isNotBlank() || it.options.any { o -> o.label.isNotBlank() } }.forEachIndexed { si, slot ->
                val slotId = dao.insertSlot(SlotEntity(recipeId = id, position = si, name = slot.name.trim().ifEmpty { "Swap" }))
                val opts = slot.options.filter { it.label.isNotBlank() || it.ings.any { i -> i.name.isNotBlank() } }
                val defaultIndex = opts.indexOfFirst { it.isDefault }.coerceAtLeast(0)
                opts.forEachIndexed { oi, o ->
                    val optionId = dao.insertOption(
                        OptionEntity(slotId = slotId, recipeId = id, position = oi, label = o.label.trim().ifEmpty { "Option ${oi + 1}" }, isDefault = oi == defaultIndex, note = o.note.trim()),
                    )
                    add(o.ings, optionId)
                }
            }
            val rows = pending.map { (i, optionId) ->
                var unit = Measure.from(i.unit)
                val parsed = Scaling.parseOrNull(i.qty)
                var note = i.note.trim()
                if (i.qty.isNotBlank() && parsed == null) note = listOf(i.qty.trim(), note).filter { it.isNotEmpty() }.joinToString(", ")
                if (parsed != null && unit == Measure.NONE) unit = Units.guessUnit(i.name)
                RecipeIngredientEntity(
                    recipeId = id, ingredientId = ingredientId(i.name), optionId = optionId, position = pos++,
                    qty = if (unit == Measure.TO_TASTE) null else parsed, unit = unit.key, prepNote = note,
                )
            }
            dao.insertRecipeIngredients(rows)
            dao.insertSteps(s.steps.map { it.text.trim() }.filter { it.isNotEmpty() }.mapIndexed { n, t -> StepEntity(recipeId = id, position = n, text = t) })
            if (track) MiseDb.mark(db, "recipe", id.toString())
            id
        }
    }

    /**
     * Imports a Mise recipe file ({"format": "mise-recipes", "recipes": [...]}). A recipe is
     * matched by title (or by an older title listed in "replaces"). New ones are added. A match
     * from the same source that is still To check (never reviewed) is replaced with the new
     * version; anything you have reviewed or edited is left alone. One unreadable recipe does
     * not stop the rest. Titles listed under "retire" (merged duplicates) are removed if never
 * reviewed. Importing the same file twice is safe.
     */
    fun importFile(json: String) {
        if (_importing.value) return
        _importing.value = true
        scope.launch {
            val message = try {
                val root = org.json.JSONObject(json)
                require(root.optString("format") == "mise-recipes") { "This is not a Mise recipe file." }
                val list = root.getJSONArray("recipes")
                val source = root.optString("source", "import").lowercase()
                val existing = dao.titleRows().associateBy { it.title.trim().lowercase() }
                val seen = mutableSetOf<String>()
                var added = 0; var updated = 0; var skipped = 0; var failed = 0; var retired = 0
                // Titles the file retires (merged into another recipe): removed only if never reviewed.
                root.optJSONArray("retire")?.let { a ->
                    for (k in 0 until a.length()) {
                        val old = existing[a.getString(k).trim().lowercase()]
                        if (old != null && old.source == source && old.status == RecipeStatus.TO_CHECK) { delete(old.id); retired++ }
                    }
                }
                for (i in 0 until list.length()) {
                    try {
                        val obj = list.getJSONObject(i)
                        val state = EditorState.fromJson(obj.toString())
                        val key = state.title.trim().lowercase()
                        if (key.isEmpty() || key in seen) { skipped++; continue }
                        seen += key
                        val olderTitles = obj.optJSONArray("replaces")?.let { a -> (0 until a.length()).map { a.getString(it).trim().lowercase() } } ?: emptyList()
                        val old = existing[key] ?: olderTitles.firstNotNullOfOrNull { existing[it] }
                        val unreviewed = old != null && old.source == source && old.status == RecipeStatus.TO_CHECK
                        when {
                            old == null -> { save(state.copy(recipeId = null), source); added++ }
                            unreviewed -> { save(state.copy(recipeId = old.id), source); updated++ }
                            else -> skipped++
                        }
                    } catch (e: Exception) {
                        failed++
                    }
                }
                buildList {
                    if (added > 0) add(if (added == 1) "1 recipe added" else "$added recipes added")
                    if (updated > 0) add("$updated updated")
                    if (skipped > 0) add("$skipped already in Mise and kept as they are")
                    if (retired > 0) add(if (retired == 1) "1 duplicate removed" else "$retired duplicates removed")
                    if (failed > 0) add("$failed could not be read")
                    if (isEmpty()) add("Nothing new in this file")
                }.joinToString(", ") + "."
            } catch (e: Exception) {
                "Could not import that file. " + (e.message ?: "")
            }
            _importResult.value = message
            _importing.value = false
        }
    }

    /** "Looks right": the recipe is reviewed, so a re-import will never replace it. */
    suspend fun markChecked(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.setStatus(id, RecipeStatus.OK, System.currentTimeMillis())
            MiseDb.mark(db, "recipe", id.toString())
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.planIdsForRecipe(id).forEach { MiseDb.markDeleted(db, "plan", it.toString()) }
            MiseDb.markDeleted(db, "recipe", id.toString())
            dao.deleteRecipe(id)
        }
    }

    // Drafts

    suspend fun draft(id: Long): EditorState? = withContext(Dispatchers.IO) {
        dao.draft(id)?.let { runCatching { EditorState.fromJson(it.json) }.getOrNull() }
    }

    /**
     * The unfinished edit of a recipe, if there is one and it is newer than the recipe itself.
     * An older draft (the recipe was changed since, here or on the other phone) is dropped.
     */
    suspend fun draftForRecipe(recipeId: Long): Pair<Long, EditorState>? = withContext(Dispatchers.IO) {
        val d = dao.draftForRecipe(recipeId) ?: return@withContext null
        val r = dao.recipe(recipeId)
        if (r != null && r.updatedAt > d.updatedAt) { dao.deleteDraft(d.id); return@withContext null }
        runCatching { EditorState.fromJson(d.json) }.getOrNull()?.let { d.id to it }
    }

    /** Writes the editor to the drafts table; returns the draft id (new or existing). */
    suspend fun saveDraft(draftId: Long?, s: EditorState): Long = withContext(Dispatchers.IO) {
        dao.upsertDraft(
            DraftEntity(
                id = draftId ?: 0, recipeId = s.recipeId,
                title = s.title.trim().ifEmpty { "Untitled recipe" },
                json = s.toJson(), updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteDraft(id: Long) = withContext(Dispatchers.IO) { dao.deleteDraft(id) }

    companion object {
        @Volatile private var instance: RecipeRepo? = null
        fun get(context: Context): RecipeRepo = instance ?: synchronized(this) {
            instance ?: RecipeRepo(MiseDb.get(context)).also { instance = it }
        }
    }
}
