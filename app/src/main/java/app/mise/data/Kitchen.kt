package app.mise.data

import android.content.Context
import androidx.room.withTransaction

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
        Prefs.storeKitchen(text.trim())
        db.withTransaction { MiseDb.mark(db, "setting", "kitchen") }
    }
}
