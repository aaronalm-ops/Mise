package app.mise.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Everything the list screen needs for one week, in one emission. */
data class WeekList(
    val dishes: Int,
    val lines: List<GroceryLine>,
    val checked: Set<String>,
    val extras: List<ListExtraEntity>,
    val aisles: Map<String, Aisle>,
    val staples: Set<String>,
)

class PlanRepo private constructor(private val db: MiseDb, private val recipes: RecipeRepo) {
    private val dao = db.recipes()
    val scope get() = recipes.scope

    fun week(weekStart: LocalDate): Flow<List<PlanEntry>> =
        dao.observePlan(weekStart.toString(), weekStart.plusDays(6).toString()).map { rows ->
            val cache = mutableMapOf<Long, Recipe?>()
            rows.mapNotNull { r ->
                val recipe = cache.getOrPut(r.recipeId) { recipes.get(r.recipeId) } ?: return@mapNotNull null
                PlanEntry(
                    id = r.id, date = LocalDate.parse(r.date), meal = Meal.from(r.meal), recipe = recipe,
                    servings = r.servings,
                    choices = r.choices.split(',').mapNotNull { it.trim().toIntOrNull() },
                )
            }
        }

    fun list(weekStart: LocalDate): Flow<WeekList> {
        val w = weekStart.toString()
        return combine(week(weekStart), dao.observeChecks(w), dao.observeExtras(w), dao.observeIngredients()) { entries, checks, extras, ings ->
            WeekList(
                dishes = entries.size,
                lines = Grocery.build(entries),
                checked = checks.toSet(),
                extras = extras,
                aisles = ings.mapNotNull { i -> Aisle.from(i.aisle)?.let { i.nameKey to it } }.toMap(),
                staples = ings.filter { it.isStaple }.map { it.nameKey }.toSet(),
            )
        }
    }

    suspend fun addEntry(date: LocalDate, meal: Meal, recipeId: Long, servings: Int, choices: List<Int>) = withContext(Dispatchers.IO) {
        dao.insertPlan(
            PlanEntryEntity(
                date = date.toString(), meal = meal.key, recipeId = recipeId, servings = servings,
                choices = choices.joinToString(","), createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateEntry(id: Long, servings: Int, choices: List<Int>) = withContext(Dispatchers.IO) {
        dao.planEntry(id)?.let { dao.updatePlan(it.copy(servings = servings, choices = choices.joinToString(","))) }
    }

    suspend fun removeEntry(id: Long) = withContext(Dispatchers.IO) { dao.deletePlan(id) }

    suspend fun setChecked(weekStart: LocalDate, key: String, checked: Boolean) = withContext(Dispatchers.IO) {
        if (checked) dao.check(ListCheckEntity(weekStart.toString(), key, System.currentTimeMillis()))
        else dao.uncheck(weekStart.toString(), key)
    }

    suspend fun addExtra(weekStart: LocalDate, name: String, qty: String) = withContext(Dispatchers.IO) {
        dao.insertExtra(ListExtraEntity(weekStart = weekStart.toString(), name = name.trim(), qty = qty.trim(), checked = false, createdAt = System.currentTimeMillis()))
    }

    suspend fun setExtraChecked(id: Long, checked: Boolean) = withContext(Dispatchers.IO) { dao.setExtraChecked(id, checked) }

    suspend fun removeExtra(id: Long) = withContext(Dispatchers.IO) { dao.deleteExtra(id) }

    suspend fun setAisle(key: String, aisle: Aisle) = withContext(Dispatchers.IO) { dao.setAisle(key, aisle.key) }

    suspend fun setStaple(key: String, staple: Boolean) = withContext(Dispatchers.IO) { dao.setStaple(key, staple) }

    companion object {
        @Volatile private var instance: PlanRepo? = null
        fun get(context: Context): PlanRepo = instance ?: synchronized(this) {
            instance ?: PlanRepo(MiseDb.get(context), RecipeRepo.get(context)).also { instance = it }
        }
    }
}
