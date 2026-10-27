package app.mise.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** All recipe reads and writes. Writes run on [scope] so leaving a screen never cancels a save. */
class RecipeRepo private constructor(private val db: MiseDb) {
    private val dao = db.recipes()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun summaries(): Flow<List<RecipeSummary>> = dao.observeSummaries().map { rows ->
        rows.map { RecipeSummary(it.id, it.title, it.cuisine, it.baseServings, it.slotCount, it.ingredientCount) }
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
            id = e.id, title = e.title, cuisine = e.cuisine,
            tags = e.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            baseServings = e.baseServings, prepMins = e.prepMins, cookMins = e.cookMins, notes = e.notes,
            ingredients = rows.filter { it.optionId == null }.map(::ing),
            slots = slots,
            steps = dao.steps(e.id).map { it.text },
        )
    }

    private suspend fun ingredientId(name: String): Long {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val key = clean.lowercase()
        dao.ingredientByKey(key)?.let { return it.id }
        val id = dao.insertIngredient(IngredientEntity(name = clean.replaceFirstChar { it.uppercase() }, nameKey = key))
        return if (id > 0) id else dao.ingredientByKey(key)!!.id
    }

    /** Saves the editor as a recipe (new or replacing the old one) and returns its id. */
    suspend fun save(s: EditorState): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val base = RecipeEntity(
                title = s.title.trim(), cuisine = s.cuisine.trim(),
                tags = s.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
                baseServings = s.serves.coerceIn(1, 99),
                prepMins = s.prep.trim().toIntOrNull(), cookMins = s.cook.trim().toIntOrNull(),
                notes = s.notes.trim(), createdAt = now, updatedAt = now,
            )
            val existing = s.recipeId?.let { dao.recipe(it) }
            val id = if (existing != null) {
                dao.updateRecipe(base.copy(id = existing.id, createdAt = existing.createdAt, source = existing.source))
                dao.deleteIngredients(existing.id); dao.deleteOptions(existing.id); dao.deleteSlots(existing.id); dao.deleteSteps(existing.id)
                existing.id
            } else {
                dao.insertRecipe(base)
            }
            var pos = 0
            val rows = mutableListOf<RecipeIngredientEntity>()
            fun add(list: List<EIng>, optionId: Long?) = list.filter { it.name.isNotBlank() }.map { i ->
                Triple(i, optionId, pos++)
            }
            val pending = add(s.ings, null).toMutableList()
            s.slots.filter { it.name.isNotBlank() || it.options.any { o -> o.label.isNotBlank() } }.forEachIndexed { si, slot ->
                val slotId = dao.insertSlot(SlotEntity(recipeId = id, position = si, name = slot.name.trim().ifEmpty { "Swap" }))
                val opts = slot.options.filter { it.label.isNotBlank() || it.ings.any { i -> i.name.isNotBlank() } }
                val defaultIndex = opts.indexOfFirst { it.isDefault }.coerceAtLeast(0)
                opts.forEachIndexed { oi, o ->
                    val optionId = dao.insertOption(
                        OptionEntity(slotId = slotId, recipeId = id, position = oi, label = o.label.trim().ifEmpty { "Option ${oi + 1}" }, isDefault = oi == defaultIndex, note = o.note.trim()),
                    )
                    pending += add(o.ings, optionId)
                }
            }
            pending.forEach { (i, optionId, p) ->
                rows += RecipeIngredientEntity(
                    recipeId = id, ingredientId = ingredientId(i.name), optionId = optionId, position = p,
                    qty = if (Measure.from(i.unit) == Measure.TO_TASTE) null else Scaling.parse(i.qty),
                    unit = i.unit, prepNote = i.note.trim(),
                )
            }
            dao.insertRecipeIngredients(rows)
            dao.insertSteps(s.steps.map { it.text.trim() }.filter { it.isNotEmpty() }.mapIndexed { n, t -> StepEntity(recipeId = id, position = n, text = t) })
            id
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteRecipe(id) }

    // Drafts

    suspend fun draft(id: Long): EditorState? = withContext(Dispatchers.IO) {
        dao.draft(id)?.let { runCatching { EditorState.fromJson(it.json) }.getOrNull() }
    }

    suspend fun draftIdForRecipe(recipeId: Long): Long? = withContext(Dispatchers.IO) { dao.draftForRecipe(recipeId)?.id }

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
