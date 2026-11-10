package app.mise.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.launch

/**
 * The kitchen profile: how this household plans and eats, in plain words. Mise AI reads it for
 * every suggestion and recipe. It is shared with the household like a recipe, and editable in
 * Settings, Mise AI.
 */
object Kitchen {
    /** Written from Aaron's answers on 10 Nov 2026; kept free of names so it suits any household. */
    val DEFAULT = """
        We cook once each evening, for that dinner and the next day's lunch, for 3 to 4 people, so dishes usually cook at 6 to 8 servings and must scale well.
        Wednesday's cook is bigger: it covers Thursday's lunch and Friday's lunch, as there is no cooking on Thursday evening. Weekends are mostly cooked as usual.
        A typical week has: one pasta; one fried rice or flavoured rice dish (such as green chicken rice); at least one gravy curry with paratha; curries with rice, or a dal-style curry with a side and rice; a bake now and then (less often now, as it does not scale well).
        Include vegetables at least once a week, as a vegetable dish or inside a curry or rice. A fully vegetarian meal is at most once a week.
        Rotate proteins evenly across the week: chicken, mutton, beef, prawns, fish (cream dory is a favourite), and vegetables. Not too much chicken. Pork less often.
        Weeknights are about an hour of cooking at most; some nights need a quick cook with ready pastes or mixes.
        Keep the heat moderate. Prefer less deep frying (air fryer or oven). Choose healthier options where affordable: brown bread, wheat over white flour.
        When a curry is planned, pair it with a rice or bread side from the library on the same evening.
    """.trimIndent()

    /** Saves the profile on this phone and shares it with the household. */
    suspend fun save(context: Context, text: String) {
        val db = MiseDb.get(context)
        db.withTransaction { Prefs.storeKitchen(text.trim()); MiseDb.mark(db, "setting", "kitchen") }
    }
}

/**
 * Household settings shared between the phones (week start, servings, no-cook slots, kitchen
 * profile), so both phones always look at the same week and the same list. Each change is saved
 * here at once and sent on the next sync; the save is never tied to a screen.
 */
object Shared {
    const val WEEK_START = "weekStart"
    const val SERVINGS = "servings"
    const val NO_COOK = "noCook"
    const val KITCHEN = "kitchen"
    val keys = listOf(WEEK_START, SERVINGS, NO_COOK, KITCHEN)

    private fun mark(context: Context, key: String) {
        val db = MiseDb.get(context)
        RecipeRepo.get(context).scope.launch { db.withTransaction { MiseDb.mark(db, "setting", key) } }
    }

    fun setWeekStart(context: Context, d: java.time.DayOfWeek) { Prefs.chooseWeekStart(d); mark(context, WEEK_START) }
    fun setServings(context: Context, n: Int) { Prefs.chooseDefaultServings(n); mark(context, SERVINGS) }
    fun setNoCook(context: Context, slots: Set<String>) { Prefs.storeNoCook(slots); mark(context, NO_COOK) }

    /** The value sent to the household for [key]. */
    fun value(key: String): String = when (key) {
        WEEK_START -> Prefs.weekStart.value.toString()
        SERVINGS -> Prefs.defaultServings.toString()
        NO_COOK -> Prefs.noCook.sorted().joinToString(",")
        KITCHEN -> Prefs.kitchen
        else -> ""
    }

    /** Applies a value received from the household (never marks it again). */
    fun apply(key: String, v: String) {
        when (key) {
            WEEK_START -> v.toIntOrNull()?.takeIf { it in 1..7 }?.let { Prefs.chooseWeekStart(java.time.DayOfWeek.of(it)) }
            SERVINGS -> v.toIntOrNull()?.takeIf { it in 0..24 }?.let { Prefs.chooseDefaultServings(it) }
            NO_COOK -> Prefs.storeNoCook(v.split(',').map { it.trim() }.filter { it.contains(':') }.toSet())
            KITCHEN -> if (v.isNotBlank()) Prefs.storeKitchen(v)
        }
    }
}
