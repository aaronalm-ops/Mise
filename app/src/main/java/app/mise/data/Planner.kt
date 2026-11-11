package app.mise.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A dish suggested for a slot, matched to a recipe in the library. */
data class Suggestion(
    val date: LocalDate,
    val meal: Meal,
    val recipe: RecipeSummary,
    val reason: String,
    /** A rice or bread dish to cook alongside a curry, also from the library. */
    val side: RecipeSummary? = null,
    /** Servings for this cook, or null for the household default. */
    val servings: Int? = null,
    /** Later meals this cook covers as leftovers (Thursday and Friday lunch). */
    val covers: List<Pair<LocalDate, Meal>> = emptyList(),
    /** Swap choices picked to keep proteins varied; empty means the recipe's defaults. */
    val choices: List<String> = emptyList(),
)

data class WeekIdeas(val items: List<Suggestion>, val summary: String, val fromAi: Boolean = false)

/**
 * Plans the week on the phone from the household's own pattern: instant, free, works offline
 * and never fails. It follows the same rules as the kitchen profile: one pasta and one fried or
 * flavoured rice a week, curries with a rice or bread side, proteins rotated (not too much
 * chicken, pork less often, vegetarian at most once), dishes not had for a while first, the
 * evening before a no-cook evening cooked bigger to cover the next two lunches.
 *
 * It tries a few hundred varied plans and keeps the best by those rules, so "Suggest again"
 * (a new seed) gives a different, equally sensible week.
 */
object Planner {
    enum class Kind { CURRY, RICE, PASTA, NOODLES, STIR_FRY, DRY, SOUP, BAKE, LIGHT, BREAKFAST, OTHER }

    enum class Protein(val label: String) {
        CHICKEN("chicken"), MUTTON("mutton"), BEEF("beef"), PRAWN("prawns"), FISH("fish"),
        PORK("pork"), TURKEY("turkey"), VEG("vegetarian"),
    }

    private val excludedTypes = setOf("Drink", "Dessert and sweet bake", "Sauce and dip")
    private val notProtein = Regex("\\b(stock|sauce|paste|cube|powder|masala|seasoning|broth|bouillon|fat|oil|gravy|dressing)\\b")
    /** Checked in order: "minced beef" is beef before "mince" is mutton. */
    private val proteinWords: List<Pair<Regex, Protein>> = listOf(
        Regex("\\b(prawns?|shrimps?)\\b") to Protein.PRAWN,
        Regex("\\b(fish|salmon|dory|tuna|basa|kingfish|hammour|sardines?|mackerel|cod|tilapia|seabass|pomfret|surmai|squid|calamari|crabs?|mussels?)\\b") to Protein.FISH,
        Regex("\\b(pork|bacon|pancetta|guanciale|ham|chorizo|pepperoni|salami)\\b") to Protein.PORK,
        Regex("\\b(beef|steak|meatballs?)\\b") to Protein.BEEF,
        Regex("\\b(mutton|lamb|goat|kheema|keema|mince|minced meat)\\b") to Protein.MUTTON,
        Regex("\\bturkey\\b") to Protein.TURKEY,
        Regex("\\bchicken\\b") to Protein.CHICKEN,
    )
    private val breadWords = Regex("\\b(paratha|parathas|naan|chapati|chapatis|roti|rotis|kulcha|puri|appam|sannas|bread|pav|phulka)\\b")
    private val mainRiceWords = Regex("\\b(fried|biryani|biriyani|nasi|goreng|risotto|paella|kabsa|machboos|mandi)\\b")

    /** Proteins of a dish in ingredient order, using the chosen swaps; vegetarian when none. */
    fun proteinsOf(r: Recipe, labels: List<String> = Choices.decode("", r)): List<Protein> {
        val names = r.ingredients.map { it.name } +
            Choices.options(r, labels).flatMap { o -> listOf(o.label) + o.ingredients.map { it.name } }
        val found = LinkedHashSet<Protein>()
        for (n in names) {
            val t = n.lowercase()
            if (notProtein.containsMatchIn(t)) continue
            proteinWords.firstOrNull { it.first.containsMatchIn(t) }?.let { found += it.second }
        }
        return found.toList().ifEmpty { listOf(Protein.VEG) }
    }

    /** The main proteins as words, for Mise AI ("mutton, prawns"). */
    fun proteins(r: Recipe): String = proteinsOf(r).take(2).joinToString(", ") { if (it == Protein.VEG) "vegetables" else it.label }

    /** A side to cook with a curry: plain rice or a bread, with no meat of its own. */
    fun isSide(r: Recipe): Boolean {
        if (r.dishType in setOf("Sandwich and burger", "Breakfast", "Drink", "Dessert and sweet bake", "Sauce and dip")) return false
        if (r.slots.isNotEmpty() || proteinsOf(r).any { it != Protein.VEG }) return false
        val t = r.title.lowercase()
        if (breadWords.containsMatchIn(t)) return true
        return (r.dishType == "Rice" || t.contains("rice")) && !mainRiceWords.containsMatchIn(t)
    }

    fun isBread(r: Recipe) = isSide(r) && breadWords.containsMatchIn(r.title.lowercase())

    /** What kind of dish it is for planning, or null when it is never a meal (drinks, sides...). */
    fun kindOf(r: Recipe): Kind? = when {
        r.dishType in excludedTypes || isSide(r) -> null
        r.dishType == "Curry" || r.dishType == "Dal" -> Kind.CURRY
        r.dishType == "Rice" -> Kind.RICE
        r.dishType == "Pasta" -> Kind.PASTA
        r.dishType == "Noodles" -> Kind.NOODLES
        r.dishType == "Stir fry" -> Kind.STIR_FRY
        r.dishType == "Dry and roast" -> Kind.DRY
        r.dishType == "Soup and stew" -> Kind.SOUP
        r.dishType == "Pizza and savoury bake" -> Kind.BAKE
        r.dishType in setOf("Finger food", "Sandwich and burger", "Salad") -> Kind.LIGHT
        r.dishType == "Breakfast" -> Kind.BREAKFAST
        else -> Kind.OTHER
    }

    data class Input(
        /** Full recipes of the library. */
        val recipes: List<Recipe>,
        /** Last date each recipe was planned (any week), from the library summaries. */
        val summaries: List<RecipeSummary>,
        /** Dishes already on this week's plan. */
        val planned: List<PlanEntry>,
        val notes: List<PlanNote>,
        /** Empty slots to fill (no-cook evenings already left out). */
        val slots: Map<LocalDate, List<Meal>>,
        /** "THURSDAY:dinner" style no-cook slots. */
        val noCook: Set<String>,
        /** Household servings for one cook; 0 means each recipe's own. */
        val servings: Int,
        val wishes: String = "",
        /** Recipes from the previous suggestion, nudged aside so "Suggest again" differs. */
        val avoid: Set<Long> = emptySet(),
        val seed: Long = System.nanoTime(),
    )

    private class Cand(
        val recipe: Recipe,
        val summary: RecipeSummary,
        val kind: Kind,
        val labels: List<String>,
        val protein: Protein,
        val minutes: Int,
        val last: LocalDate?,
        val text: String,
    )

    private class Pick(val date: LocalDate, val meal: Meal, val c: Cand, val fixed: Boolean, val bigCook: Boolean)

    private val mainMeals = setOf(Meal.LUNCH, Meal.DINNER)

    private fun fits(meal: Meal, k: Kind) = when (meal) {
        Meal.BREAKFAST -> k == Kind.BREAKFAST
        Meal.SNACK -> k == Kind.LIGHT
        else -> k != Kind.BREAKFAST
    }

    /** Words from the wishes box: wanted, and unwanted after "no", "not", "without", "less". */
    private val fillers = setOf(
        "the", "once", "twice", "this", "week", "some", "more", "please", "with", "for", "dish", "dishes", "night", "nights",
        "weekday", "weekdays", "weekend", "lighter", "light", "quick", "fast", "easy", "simple", "one", "two", "time", "times", "only", "like", "want",
    )

    private class Wishes(text: String) {
        val wanted = mutableSetOf<String>()
        val unwanted = mutableSetOf<String>()
        /** "less chicken": allowed, just less likely. */
        val fewer = mutableSetOf<String>()
        val quick: Boolean
        init {
            val t = text.lowercase()
            quick = Regex("\\b(quick|fast|easy|light|lighter|simple)\\b").containsMatchIn(t)
            // "no beef, fish once": a "no" lasts until the comma, full stop, "and" or "but".
            for (clause in t.split(Regex("[,.;!\\n]|\\band\\b|\\bbut\\b"))) {
                var negate = false
                var soft = false
                for (w in clause.replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() }) {
                    if (w in setOf("no", "not", "without", "skip", "avoid")) { negate = true; continue }
                    if (w in setOf("less", "fewer")) { soft = true; continue }
                    if (w.length < 3 || w in fillers) continue
                    val stem = w.removeSuffix("s")
                    when {
                        negate -> unwanted += stem
                        soft -> fewer += stem
                        else -> wanted += stem
                    }
                }
            }
        }
        fun matches(words: Set<String>, c: Cand) = words.any { w -> c.text.contains(w) }
    }

    fun suggest(input: Input): WeekIdeas {
        val rnd = Random(input.seed)
        val byId = input.summaries.associateBy { it.id }
        val taken = input.planned.filter { !it.leftovers }.map { it.recipe.id }.toSet()
        val wishes = Wishes(input.wishes)

        // Candidates: every meal dish, one per protein option when a swap changes the protein.
        val cands = input.recipes.filter { it.id !in taken }.flatMap { r ->
            val kind = kindOf(r) ?: return@flatMap emptyList()
            val s = byId[r.id] ?: return@flatMap emptyList()
            val last = s.lastPlanned?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val minutes = (r.prepMins ?: 0) + (r.cookMins ?: 0)
            val base = Choices.decode("", r)
            val variants = if (r.slots.isEmpty()) listOf(base) else r.slots[0].options.map { o -> listOf(o.label) + base.drop(1) }
            variants.map { labels -> labels to proteinsOf(r, labels).first() }
                .distinctBy { it.second }
                .map { (labels, p) ->
                    val text = listOf(r.title, r.cuisine, r.dishType, kind.name, p.label, labels.joinToString(" ")).joinToString(" ").lowercase()
                    Cand(r, s, kind, labels, p, minutes, last, text)
                }
        }.filter { c -> wishes.unwanted.none { c.text.contains(it) } }

        val fixed = input.planned.filter { !it.leftovers }.mapNotNull { e ->
            val kind = kindOf(e.recipe) ?: return@mapNotNull null
            val s = byId[e.recipe.id] ?: RecipeSummary(e.recipe.id, e.recipe.title, e.recipe.cuisine, e.recipe.dishType, e.recipe.baseServings, 0, 0, false, "", null)
            val p = proteinsOf(e.recipe, e.choices).first()
            Pick(e.date, e.meal, Cand(e.recipe, s, kind, e.choices, p, 0, null, ""), fixed = true, bigCook = false)
        }

        val slotList = input.slots.flatMap { (d, meals) -> meals.map { d to it } }.sortedWith(compareBy({ it.first }, { it.second.ordinal }))
        val bigCookDays = slotList.filter { (d, m) -> m == Meal.DINNER && "${d.plusDays(1).dayOfWeek.name}:dinner" in input.noCook }.map { it.first }.toSet()
        val mainSlotCount = slotList.count { it.second in mainMeals } + fixed.count { it.meal in mainMeals }

        fun local(c: Cand, d: LocalDate, meal: Meal, big: Boolean): Double {
            var s = 0.0
            val since = c.last?.let { ChronoUnit.DAYS.between(it, d) }
            s += when {
                since == null -> 2.0
                kotlin.math.abs(since) <= 6 -> -4.0
                else -> min(since, 60L) / 60.0 * 3.0
            }
            if (c.recipe.id in input.avoid) s -= 3.0
            when (c.kind) {
                Kind.LIGHT -> s += if (meal == Meal.DINNER) -4.0 else 0.5
                Kind.BAKE -> s -= 0.8
                Kind.NOODLES, Kind.STIR_FRY, Kind.DRY, Kind.SOUP -> s += 0.3
                else -> {}
            }
            if (c.recipe.status == RecipeStatus.TO_CHECK) s -= 0.3
            val weekday = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            if (weekday && c.minutes > 75) s -= 1.5
            if (wishes.quick && c.minutes in 1..40) s += 1.5
            if (big) s += when (c.kind) {
                Kind.CURRY, Kind.SOUP, Kind.RICE -> 1.5
                Kind.PASTA, Kind.NOODLES, Kind.LIGHT, Kind.STIR_FRY -> -1.5
                else -> 0.0
            }
            if (wishes.matches(wishes.wanted, c)) s += 3.0
            if (wishes.matches(wishes.fewer, c)) s -= 3.0
            return s
        }

        /** Rules over the whole week. [complete] adds the "every week has one" checks. */
        fun week(picks: List<Pick>, complete: Boolean): Double {
            val mains = picks.filter { it.meal in mainMeals }.sortedBy { it.date }
            var s = 0.0
            fun count(k: Kind) = mains.count { it.c.kind == k }
            val pasta = count(Kind.PASTA); val rice = count(Kind.RICE); val curry = count(Kind.CURRY); val bake = count(Kind.BAKE)
            if (pasta > 1) s -= 4.0 * (pasta - 1)
            if (rice > 1) s -= 4.0 * (rice - 1)
            if (bake > 1) s -= 3.0 * (bake - 1)
            s += 1.2 * min(curry, 3) - 2.0 * max(0, curry - 3)
            if (complete && mainSlotCount >= 4) {
                s += if (pasta == 1) 4.0 else if (pasta == 0) -3.0 else 0.0
                s += if (rice == 1) 4.0 else if (rice == 0) -3.0 else 0.0
                if (curry == 0) s -= 3.0
            } else {
                if (pasta == 1) s += 1.5
                if (rice == 1) s += 1.5
            }
            val byProtein = mains.groupingBy { it.c.protein }.eachCount()
            byProtein.forEach { (p, n) ->
                if (n > 1) s -= (if (p == Protein.VEG) 8.0 else 3.0) * (n - 1)
                when (p) {
                    Protein.CHICKEN -> s -= 1.0 * n
                    Protein.TURKEY -> s -= 0.5 * n
                    Protein.PORK -> s -= 2.0 * n
                    Protein.PRAWN, Protein.FISH -> s += 1.5
                    else -> {}
                }
            }
            s += 0.8 * byProtein.size
            val dinners = mains.filter { it.meal == Meal.DINNER }
            for (i in 1 until dinners.size) {
                if (ChronoUnit.DAYS.between(dinners[i - 1].date, dinners[i].date) == 1L) {
                    if (dinners[i - 1].c.protein == dinners[i].c.protein) s -= 3.0
                    if (dinners[i - 1].c.kind == dinners[i].c.kind) s -= 1.5
                }
            }
            return s
        }

        // Each dish's own score per slot never changes between rounds: worked out once.
        val localScores = slotList.map { (d, meal) ->
            DoubleArray(cands.size) { i -> if (fits(meal, cands[i].kind)) local(cands[i], d, meal, d in bigCookDays) else Double.NaN }
        }
        var best: List<Pick>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val rounds = if (slotList.size <= 2) 80 else 200
        repeat(rounds) {
            val picks = fixed.toMutableList()
            val used = mutableSetOf<Long>()
            for ((si, slot) in slotList.withIndex()) {
                val (d, meal) = slot
                val big = d in bigCookDays
                var top: Cand? = null
                var topScore = Double.NEGATIVE_INFINITY
                for ((ci, c) in cands.withIndex()) {
                    val own = localScores[si][ci]
                    if (own.isNaN() || c.recipe.id in used) continue
                    val trial = picks + Pick(d, meal, c, false, big)
                    // Gumbel noise: each round explores a different, still sensible, week.
                    val noise = -ln(-ln(rnd.nextDouble().coerceIn(1e-9, 1 - 1e-9))) * 1.4
                    val v = own + week(trial, complete = false) + noise
                    if (v > topScore) { topScore = v; top = c }
                }
                top?.let { picks += Pick(d, meal, it, false, big); used += it.recipe.id }
            }
            val total = picks.filter { !it.fixed }.sumOf { local(it.c, it.date, it.meal, it.bigCook) } + week(picks, complete = true)
            if (total > bestScore) { bestScore = total; best = picks }
        }

        val chosen = best.orEmpty().filter { !it.fixed }.sortedWith(compareBy({ it.date }, { it.meal.ordinal }))
        val sides = input.recipes.filter { isSide(it) }
        val sideUse = mutableMapOf<Long, Int>()
        var breadUsed = input.planned.any { isBread(it.recipe) }
        val all = best.orEmpty()
        val items = chosen.map { p ->
            val r = p.c.recipe
            // A curry gets a side: one gravy a week with bread when there is a bread, otherwise rice.
            val side = if (p.c.kind == Kind.CURRY && p.meal == Meal.DINNER && sides.isNotEmpty()) {
                val breads = sides.filter { isBread(it) }
                val pool = if (!breadUsed && breads.isNotEmpty() && r.dishType == "Curry") breads.also { breadUsed = true }
                else sides.filter { !isBread(it) }.ifEmpty { sides }
                pool.minWithOrNull(compareBy({ sideUse[it.id] ?: 0 }, { byId[it.id]?.lastPlanned ?: "" }, { rnd.nextInt() }))
                    ?.also { sideUse[it.id] = (sideUse[it.id] ?: 0) + 1 }?.let { byId[it.id] }
            } else null
            val covers = if (p.bigCook) {
                listOf(p.date.plusDays(1) to Meal.LUNCH, p.date.plusDays(2) to Meal.LUNCH).filter { (d, m) ->
                    input.planned.none { it.date == d && it.meal == m } && input.notes.none { it.date == d && it.meal == m } &&
                        chosen.none { it.date == d && it.meal == m }
                }
            } else emptyList()
            val base = input.servings.takeIf { it > 0 }
            val servings = if (covers.isNotEmpty()) min(24, (base ?: 8) * 3 / 2) else null
            val labels = if (r.slots.isEmpty()) emptyList() else p.c.labels
            Suggestion(p.date, p.meal, p.c.summary, reason(p, all, covers, side), side, servings, covers, labels)
        }
        return WeekIdeas(items, summary(chosen, all))
    }

    private fun day(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)

    private fun reason(p: Pick, all: List<Pick>, covers: List<Pair<LocalDate, Meal>>, side: RecipeSummary?): String {
        val c = p.c
        val firstOfProtein = all.filter { it.meal in mainMeals && it.c.protein == c.protein }.minByOrNull { it.date } === p
        val since = c.last?.let { ChronoUnit.DAYS.between(it, p.date) }
        return when {
            covers.isNotEmpty() -> "Big cook: also " + covers.joinToString(" and ") { day(it.first) } + " lunch"
            c.kind == Kind.PASTA -> "The week's pasta"
            c.kind == Kind.RICE -> "The week's fried or flavoured rice"
            c.protein == Protein.VEG && p.meal in mainMeals -> "The week's vegetarian dish"
            (c.protein == Protein.PRAWN || c.protein == Protein.FISH) && firstOfProtein -> "${c.protein.label.replaceFirstChar { it.uppercase() }} for a change"
            since != null && since >= 21 -> "Not had in ${since / 7} weeks"
            c.kind == Kind.CURRY && side != null -> "A curry, with ${side.title.lowercase()}"
            since == null -> "Not on your plan before"
            c.minutes in 1..40 -> "Quick: about ${c.minutes} minutes"
            else -> "Keeps the week varied"
        }
    }

    private fun summary(chosen: List<Pick>, all: List<Pick>): String {
        if (chosen.isEmpty()) return ""
        val proteins = all.filter { it.meal in mainMeals }.map { it.c.protein }.distinct().map { it.label }
        val parts = mutableListOf<String>()
        if (proteins.isNotEmpty()) parts += "Proteins: " + joinAnd(proteins) + "."
        val kinds = all.map { it.c.kind }
        val has = listOfNotNull("a pasta".takeIf { Kind.PASTA in kinds }, "a fried or flavoured rice".takeIf { Kind.RICE in kinds }, "a curry".takeIf { Kind.CURRY in kinds })
        if (has.isNotEmpty()) parts += "The week has " + joinAnd(has) + "."
        return parts.joinToString(" ")
    }

    private fun joinAnd(xs: List<String>) = if (xs.size <= 1) xs.joinToString() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()
}
