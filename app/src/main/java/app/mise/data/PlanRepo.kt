package app.mise.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One row on the grocery list: a merged recipe line or a hand-added item. */
data class ListItem(
    /** "ing:<key>" for recipe lines, "extra:<id>" for hand-added items. */
    val id: String,
    val key: String,
    val name: String,
    val amount: String,
    val detail: String?,
    val dishes: List<String>,
    val aisle: Aisle,
    val checked: Boolean,
    val checkedBy: String,
    val staple: Boolean,
    val extraId: Long? = null,
    /** "+500 g more" when a dish added after ticking needs more. */
    val more: String? = null,
    /** Carried over from an earlier week, not yet bought. */
    val carried: Boolean = false,
    val line: GroceryLine? = null,
    /** The week the tick belongs to (earlier weeks for carried lines). */
    val weekStart: LocalDate,
    /** Pantry level when kept at home: Pantry.LOW or Pantry.OUT puts it on the list. */
    val pantry: Int = Pantry.NONE,
)

/** How much of a kept-at-home item is left. Stored in the ingredient's staple choice. */
object Pantry {
    const val NONE = 0
    const val HAVE = 1
    const val LOW = 2
    const val OUT = 3
}

/** One item kept at home, for the Pantry tab. */
data class PantryItem(val key: String, val name: String, val level: Int, val aisle: Aisle)

/** Everything the list screen needs for one week, in one emission. */
data class WeekList(
    val dishes: Int,
    val items: List<ListItem>,
    /** Unbought recipe lines from last week that this week does not already need. */
    val lastWeek: List<ListItem>,
)

/** A past week that can be repeated. */
data class PastWeek(val weekStart: LocalDate, val dishes: Int)

class PlanRepo private constructor(private val db: MiseDb, private val recipes: RecipeRepo) {
    private val dao = db.recipes()
    val scope get() = recipes.scope

    fun week(weekStart: LocalDate): Flow<List<PlanEntry>> =
        dao.observePlan(weekStart.toString(), weekStart.plusDays(6).toString()).map { rows ->
            val cache = mutableMapOf<Long, Recipe?>()
            rows.mapNotNull { r ->
                val recipe = cache[r.recipeId] ?: recipes.get(r.recipeId).also { cache[r.recipeId] = it } ?: return@mapNotNull null
                PlanEntry(
                    id = r.id, date = LocalDate.parse(r.date), meal = Meal.from(r.meal), recipe = recipe,
                    servings = r.servings, choices = Choices.decode(r.choices, recipe), leftovers = r.leftovers, createdAt = r.createdAt,
                )
            }
        }

    fun notes(weekStart: LocalDate): Flow<List<PlanNote>> =
        dao.observeNotes(weekStart.toString(), weekStart.plusDays(6).toString()).map { rows ->
            rows.map { PlanNote(it.id, LocalDate.parse(it.date), Meal.from(it.meal), it.text) }
        }

    fun recentRecipeIds(): Flow<List<Long>> = dao.observeRecentRecipeIds(8)

    fun recentExtraNames(): Flow<List<String>> = dao.observeRecentExtraNames(12)

    /** Household settings per merged ingredient key: aisle, staple, and "Same as" merges. */
    private data class KeySettings(
        val aisles: Map<String, Aisle>,
        /** Kept at home and in stock: left off the list. */
        val staples: Set<String>,
        val aliases: Map<String, String>,
        /** Kept at home but low or out: on the list, and restocked when ticked. */
        val short: Map<String, Int>,
        val names: Map<String, String>,
    )

    private fun settings(ings: List<IngredientEntity>): KeySettings {
        val aliases = ings.filter { it.aliasOf.isNotBlank() }.associate { Names.keyOf(it.name) to it.aliasOf }
        fun k(i: IngredientEntity) = Names.keyOf(i.name).let { aliases[it] ?: it }
        val aisles = mutableMapOf<String, Aisle>()
        val choice = mutableMapOf<String, Int>()
        ings.forEach { i ->
            val key = k(i)
            Aisle.from(i.aisle)?.let { aisles[key] = it }
            if (i.stapleChoice >= 0) choice[key] = i.stapleChoice
        }
        val keys = ings.map { k(it) }.toSet() + Names.defaultStaples
        val staples = keys.filter { key -> choice[key]?.let { it == Pantry.HAVE } ?: (key in Names.defaultStaples) }.toSet()
        val short = choice.filterValues { it == Pantry.LOW || it == Pantry.OUT }
        val names = ings.groupBy { k(it) }.mapValues { (_, rows) -> rows.first().name }
        return KeySettings(aisles, staples, aliases, short, names)
    }

    fun list(weekStart: LocalDate, until: LocalDate? = null): Flow<WeekList> {
        val w = weekStart.toString()
        val prev = weekStart.minusWeeks(1)
        val current = combine(week(weekStart), dao.observeChecks(w), dao.observeExtras(w)) { e, c, x -> Triple(e, c, x) }
        val previous = combine(week(prev), dao.observeChecks(prev.toString())) { e, c -> e to c }
        return combine(current, previous, dao.observeIngredients()) { (entries, checks, extras), (prevEntries, prevChecks), ings ->
            val s = settings(ings)
            val shown = if (until == null) entries else entries.filter { !it.date.isAfter(until) }
            val lines = Grocery.build(shown, s.aliases)
            val checkMap = checks.associateBy { it.itemKey }
            val items = lines.map { line -> lineItem(line, checkMap[line.key], s, weekStart) } +
                extras.map { x ->
                    val k = Names.keyOf(x.name)
                    ListItem(
                        id = "extra:${x.id}", key = k, name = x.name, amount = x.qty, detail = null, dishes = emptyList(),
                        aisle = Aisle.from(x.aisle) ?: s.aisles[k] ?: Aisle.guess(x.name), checked = x.checked, checkedBy = x.checkedBy,
                        staple = false, extraId = x.id, carried = x.weekStart != w, weekStart = LocalDate.parse(x.weekStart),
                    )
                }
            val thisWeekKeys = lines.map { it.key }.toSet()
            val prevCheckMap = prevChecks.associateBy { it.itemKey }
            val lastWeek = Grocery.build(prevEntries, s.aliases)
                .filter { it.key !in thisWeekKeys && it.key !in s.staples }
                .map { lineItem(it, prevCheckMap[it.key], s, prev).copy(carried = true, id = "prev:${it.key}") }
                .filter { !it.checked }
            // Pantry items running low or out that no dish needs this week: restock lines.
            val restock = s.short.filterKeys { it !in thisWeekKeys }.map { (key, level) ->
                val name = s.names[key] ?: key.replaceFirstChar { it.uppercase() }
                ListItem(
                    id = "pantry:$key", key = key, name = name, amount = "", detail = null, dishes = emptyList(),
                    aisle = s.aisles[key] ?: Aisle.guess(name), checked = false, checkedBy = "", staple = false,
                    weekStart = weekStart, pantry = level,
                )
            }
            WeekList(dishes = shown.count { !it.leftovers }, items = items + restock, lastWeek = lastWeek)
        }
    }

    private fun lineItem(line: GroceryLine, check: ListCheckEntity?, s: KeySettings, week: LocalDate): ListItem {
        var checked = check != null
        var more: String? = null
        if (check != null && check.amount > 0 && line.measure > check.amount * 1.02) {
            checked = false
            val (v, u) = Scaling.normalise(line.measure - check.amount, line.measureUnit)
            more = "+" + Scaling.format(v, u).ifBlank { Scaling.plain(v) } + " more"
        }
        return ListItem(
            id = "ing:${line.key}", key = line.key, name = line.name, amount = line.amount, detail = line.detail,
            dishes = line.dishes, aisle = s.aisles[line.key] ?: Aisle.guess(line.name), checked = checked,
            checkedBy = check?.checkedBy ?: "", staple = line.key in s.staples, more = more, line = line, weekStart = week,
            pantry = s.short[line.key] ?: Pantry.NONE,
        )
    }

    // ---------- Plan writes (each with its sync mark in the same transaction) ----------

    private suspend fun <T> tx(block: suspend () -> T): T = withContext(Dispatchers.IO) { db.withTransaction { block() } }

    private fun householdServings(recipe: Recipe) = Prefs.defaultServings.takeIf { it > 0 } ?: recipe.baseServings

    fun defaultServings(recipe: Recipe) = householdServings(recipe)

    suspend fun addEntry(date: LocalDate, meal: Meal, recipeId: Long, servings: Int, labels: List<String>, leftovers: Boolean = false): Long = tx {
        val id = dao.insertPlan(
            PlanEntryEntity(
                date = date.toString(), meal = meal.key, recipeId = recipeId, servings = servings,
                choices = Choices.encode(labels), createdAt = System.currentTimeMillis(), leftovers = leftovers,
            ),
        )
        MiseDb.mark(db, "plan", id.toString())
        id
    }

    suspend fun updateEntry(id: Long, date: LocalDate, meal: Meal, recipeId: Long, servings: Int, labels: List<String>, leftovers: Boolean) = tx {
        dao.planEntry(id)?.let {
            dao.updatePlan(it.copy(date = date.toString(), meal = meal.key, recipeId = recipeId, servings = servings, choices = Choices.encode(labels), leftovers = leftovers))
            MiseDb.mark(db, "plan", id.toString())
        }
    }

    /** Removes an entry and returns it, so Undo can put it back exactly. */
    suspend fun removeEntry(id: Long): PlanEntryEntity? = tx {
        val e = dao.planEntry(id)
        MiseDb.markDeleted(db, "plan", id.toString())
        dao.deletePlan(id)
        e
    }

    suspend fun restoreEntry(e: PlanEntryEntity) = tx {
        val id = dao.insertPlan(e.copy(id = 0))
        MiseDb.mark(db, "plan", id.toString())
    }

    suspend fun addNote(date: LocalDate, meal: Meal, text: String): Long = tx {
        val id = dao.insertNote(PlanNoteEntity(date = date.toString(), meal = meal.key, text = text.trim(), createdAt = System.currentTimeMillis()))
        MiseDb.mark(db, "note", id.toString())
        id
    }

    suspend fun updateNote(id: Long, date: LocalDate, meal: Meal, text: String) = tx {
        dao.note(id)?.let {
            dao.updateNote(it.copy(date = date.toString(), meal = meal.key, text = text.trim()))
            MiseDb.mark(db, "note", id.toString())
        }
    }

    suspend fun removeNote(id: Long): PlanNoteEntity? = tx {
        val n = dao.note(id)
        MiseDb.markDeleted(db, "note", id.toString())
        dao.deleteNote(id)
        n
    }

    suspend fun restoreNote(n: PlanNoteEntity) = tx {
        val id = dao.insertNote(n.copy(id = 0))
        MiseDb.mark(db, "note", id.toString())
    }

    /** Weeks before [before] that have dishes, newest first. */
    suspend fun pastWeeks(before: LocalDate): List<PastWeek> = withContext(Dispatchers.IO) {
        dao.pastPlanDates(before.toString()).map { Grocery.weekStartOf(LocalDate.parse(it), Prefs.weekStart) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.key }.take(12)
            .map { PastWeek(it.key, it.value) }
    }

    /** Copies a past week's dishes into [to], day for day. Skips any already there. Returns how many. */
    suspend fun copyWeek(from: LocalDate, to: LocalDate): Int = tx {
        val source = dao.planRange(from.toString(), from.plusDays(6).toString())
        val target = dao.planRange(to.toString(), to.plusDays(6).toString())
        var n = 0
        source.forEach { e ->
            val offset = java.time.temporal.ChronoUnit.DAYS.between(from, LocalDate.parse(e.date))
            val date = to.plusDays(offset).toString()
            if (target.none { it.date == date && it.meal == e.meal && it.recipeId == e.recipeId }) {
                val id = dao.insertPlan(e.copy(id = 0, date = date, createdAt = System.currentTimeMillis() + n))
                MiseDb.mark(db, "plan", id.toString())
                n++
            }
        }
        n
    }

    // ---------- List writes ----------

    suspend fun setChecked(item: ListItem, checked: Boolean, by: String) = tx {
        val week = item.weekStart.toString()
        // Bought: a pantry item that was low or out is back in stock (Undo puts the level back).
        if (item.pantry != Pantry.NONE) {
            setLevelIn(item.key, item.name, if (checked) Pantry.HAVE else item.pantry)
            if (item.id.startsWith("pantry:")) return@tx
        }
        if (item.extraId != null) {
            dao.extra(item.extraId)?.let {
                dao.updateExtra(it.copy(checked = checked, checkedBy = if (checked) by else ""))
                MiseDb.mark(db, "extra", it.id.toString())
            }
        } else {
            if (checked) dao.check(ListCheckEntity(week, item.key, System.currentTimeMillis(), item.line?.measure ?: 0.0, by))
            else dao.uncheck(week, item.key)
            MiseDb.mark(db, "check", "$week|${item.key}")
        }
    }

    suspend fun addExtra(weekStart: LocalDate, name: String, qty: String, aisle: Aisle?): Long = tx {
        val id = dao.insertExtra(
            ListExtraEntity(weekStart = weekStart.toString(), name = name.trim().replaceFirstChar { it.uppercase() }, qty = qty.trim(), checked = false, createdAt = System.currentTimeMillis(), aisle = aisle?.key ?: ""),
        )
        MiseDb.mark(db, "extra", id.toString())
        id
    }

    suspend fun updateExtra(id: Long, name: String, qty: String, aisle: Aisle) = tx {
        dao.extra(id)?.let {
            dao.updateExtra(it.copy(name = name.trim(), qty = qty.trim(), aisle = aisle.key))
            MiseDb.mark(db, "extra", id.toString())
        }
    }

    suspend fun removeExtra(id: Long): ListExtraEntity? = tx {
        val e = dao.extra(id)
        MiseDb.markDeleted(db, "extra", id.toString())
        dao.deleteExtra(id)
        e
    }

    suspend fun restoreExtra(e: ListExtraEntity) = tx {
        val id = dao.insertExtra(e.copy(id = 0))
        MiseDb.mark(db, "extra", id.toString())
    }

    /** Every catalogue row whose merged key is [key]; created if the name is new. */
    private suspend fun rowsFor(key: String, name: String): List<IngredientEntity> {
        val all = dao.allIngredients()
        val aliases = all.filter { it.aliasOf.isNotBlank() }.associate { Names.keyOf(it.name) to it.aliasOf }
        val rows = all.filter { (Names.keyOf(it.name).let { k -> aliases[k] ?: k }) == key }
        if (rows.isNotEmpty()) return rows
        val clean = name.trim()
        dao.insertIngredient(IngredientEntity(name = clean.replaceFirstChar { it.uppercase() }, nameKey = clean.lowercase()))
        return listOfNotNull(dao.ingredientByKey(clean.lowercase()))
    }

    suspend fun setAisle(key: String, name: String, aisle: Aisle) = tx {
        rowsFor(key, name).forEach { dao.setAisle(it.id, aisle.key); MiseDb.mark(db, "ingredient", it.nameKey) }
    }

    suspend fun setStaple(key: String, name: String, staple: Boolean) = setLevel(key, name, if (staple) Pantry.HAVE else Pantry.NONE)

    /** Have, low, out, or not kept at home (Pantry.NONE). */
    suspend fun setLevel(key: String, name: String, level: Int) = tx { setLevelIn(key, name, level) }

    private suspend fun setLevelIn(key: String, name: String, level: Int) {
        rowsFor(key, name).forEach { dao.setStapleChoice(it.id, level); MiseDb.mark(db, "ingredient", it.nameKey) }
    }

    /** "Same as...": merges [fromKey] into [toKey] on the list from now on. */
    suspend fun setSameAs(fromKey: String, fromName: String, toKey: String) = tx {
        rowsFor(fromKey, fromName).forEach { dao.setAlias(it.id, toKey); MiseDb.mark(db, "ingredient", it.nameKey) }
    }

    /** Everything kept at home and how much is left, for the Pantry tab. */
    fun pantry(): Flow<List<PantryItem>> = dao.observeIngredients().map { ings ->
        val s = settings(ings)
        ings.groupBy { Names.keyOf(it.name).let { k -> s.aliases[k] ?: k } }
            .mapNotNull { (key, rows) ->
                val level = when {
                    key in s.staples -> Pantry.HAVE
                    else -> s.short[key] ?: return@mapNotNull null
                }
                val name = rows.first().name
                PantryItem(key, name, level, s.aisles[key] ?: Aisle.guess(name))
            }
            .sortedBy { it.name.lowercase() }
    }

    companion object {
        @Volatile private var instance: PlanRepo? = null
        fun get(context: Context): PlanRepo = instance ?: synchronized(this) {
            instance ?: PlanRepo(MiseDb.get(context), RecipeRepo.get(context)).also { instance = it }
        }
    }
}
