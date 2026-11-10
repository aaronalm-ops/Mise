package app.mise.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.DayOfWeek

/**
 * App settings, kept on this phone. Each value is also Compose state, so screens update the
 * moment a setting changes.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    enum class Theme { SYSTEM, LIGHT, DARK }

    var theme by mutableStateOf(Theme.SYSTEM); private set
    /** 0 = use each recipe's own servings. */
    var defaultServings by mutableIntStateOf(0); private set
    var weekStart by mutableStateOf(DayOfWeek.MONDAY); private set
    var reminderOn by mutableStateOf(false); private set
    var reminderDay by mutableStateOf(DayOfWeek.SATURDAY); private set
    var reminderHour by mutableIntStateOf(18); private set
    var listHintSeen by mutableStateOf(false); private set
    /** How this household eats, in plain words, for Mise AI. Shared with the household. */
    var kitchen by mutableStateOf(""); private set
    /** Slots with no cooking, as "THURSDAY:dinner". Shared with the household. */
    var noCook by mutableStateOf(setOf<String>()); private set
    /** Whether this account may use Mise AI (checked with the server, cached). */
    var aiAllowed by mutableStateOf(true); private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("mise_settings", Context.MODE_PRIVATE)
        theme = runCatching { Theme.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(Theme.SYSTEM)
        defaultServings = sp.getInt("defaultServings", 0)
        weekStart = DayOfWeek.of(sp.getInt("weekStart", 1).coerceIn(1, 7))
        reminderOn = sp.getBoolean("reminderOn", false)
        reminderDay = DayOfWeek.of(sp.getInt("reminderDay", 6).coerceIn(1, 7))
        reminderHour = sp.getInt("reminderHour", 18)
        listHintSeen = sp.getBoolean("listHintSeen", false)
        kitchen = sp.getString("kitchen", null) ?: Kitchen.DEFAULT
        noCook = sp.getStringSet("noCook", null)?.toSet() ?: setOf("THURSDAY:dinner")
        aiAllowed = sp.getBoolean("aiAllowed", true)
    }

    fun chooseTheme(t: Theme) { theme = t; sp.edit().putString("theme", t.name).apply() }
    fun chooseDefaultServings(n: Int) { defaultServings = n; sp.edit().putInt("defaultServings", n).apply() }
    fun chooseWeekStart(d: DayOfWeek) { weekStart = d; sp.edit().putInt("weekStart", d.value).apply() }
    fun setReminder(on: Boolean, day: DayOfWeek = reminderDay, hour: Int = reminderHour) {
        reminderOn = on; reminderDay = day; reminderHour = hour
        sp.edit().putBoolean("reminderOn", on).putInt("reminderDay", day.value).putInt("reminderHour", hour).apply()
    }
    /** Stores the kitchen profile (use Kitchen.save to also share it with the household). */
    fun storeKitchen(text: String) { kitchen = text; sp.edit().putString("kitchen", text).apply() }
    fun storeNoCook(slots: Set<String>) { noCook = slots; sp.edit().putStringSet("noCook", slots).apply() }
    fun storeAiAllowed(on: Boolean) { aiAllowed = on; sp.edit().putBoolean("aiAllowed", on).apply() }
    fun isNoCook(day: DayOfWeek, meal: String) = "${day.name}:$meal" in noCook
    fun markListHintSeen() { listHintSeen = true; sp.edit().putBoolean("listHintSeen", true).apply() }

    fun flag(name: String): Boolean = sp.getBoolean(name, false)
    fun setFlag(name: String) = sp.edit().putBoolean(name, true).apply()
}
