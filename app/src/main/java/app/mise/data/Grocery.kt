package app.mise.data

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

enum class Meal(val key: String, val label: String) {
    BREAKFAST("breakfast", "Breakfast"),
    LUNCH("lunch", "Lunch"),
    DINNER("dinner", "Dinner"),
    SNACK("snack", "Snack");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: DINNER
    }
}

/** A dish on the plan, joined with its recipe so screens can show and scale it. */
data class PlanEntry(
    val id: Long,
    val date: LocalDate,
    val meal: Meal,
    val recipe: Recipe,
    val servings: Int,
    /** Chosen option label per swap. */
    val choices: List<String>,
    /** Leftovers of a dish cooked earlier: shown on the plan, adds nothing to the list. */
    val leftovers: Boolean = false,
    val createdAt: Long = 0,
) {
    val chosenOptions: List<SlotOption> get() = Choices.options(recipe, choices)
}

/** Eating out, takeaway or any note on the plan. Adds nothing to the list. */
data class PlanNote(val id: Long, val date: LocalDate, val meal: Meal, val text: String)

/** One merged line of the grocery list. */
data class GroceryLine(
    val key: String,
    val name: String,
    /** What to buy, in shopping terms ("2 bulbs", "1.2 kg", "2 × 200 ml"). */
    val amount: String,
    /** The cooking amount when it differs ("16 cloves"), or null. */
    val detail: String?,
    val dishes: List<String>,
    /** Total in the line's main unit, used to reopen a ticked line when more is needed. */
    val measure: Double,
    val measureUnit: Measure,
)

/** Supermarket sections, in walking order. UAE stores sell pork in its own licensed section. */
enum class Aisle(val key: String, val label: String) {
    PRODUCE("produce", "Fruit and veg"),
    HERBS("herbs", "Herbs"),
    MEAT("meat", "Meat and fish"),
    PORK("pork", "Pork section"),
    DAIRY("dairy", "Dairy and eggs"),
    BAKERY("bakery", "Bakery"),
    DRY("dry", "Rice, pasta and pulses"),
    SPICES("spices", "Spices and masalas"),
    PANTRY("pantry", "Oils, sauces and tins"),
    BAKING("baking", "Baking"),
    DRINKS("drinks", "Drinks"),
    FROZEN("frozen", "Frozen"),
    OTHER("other", "Other");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key }

        private val keywords: Map<String, Aisle> = buildMap {
            fun put(a: Aisle, vararg w: String) = w.forEach { this[it] = a }
            put(HERBS, "coriander", "coriander leaves", "cilantro", "mint", "mint leaves", "parsley", "basil", "thai basil", "curry leaf", "dill", "thyme", "rosemary", "spring onion", "green onion", "lemongrass", "galangal")
            put(MEAT, "chicken", "chicken thigh", "chicken wing", "mutton", "lamb", "beef", "minced meat", "mince", "kheema", "meatball", "patty", "beef patty", "turkey", "prawn", "shrimp", "fish", "kingfish", "salmon", "tuna steak", "crab", "squid", "corned beef", "sausage")
            put(PORK, "bacon", "pancetta", "guanciale", "pork", "ham", "salami", "pepperoni", "chorizo", "pork sausage", "pork mince", "pork chop", "pork belly")
            put(DAIRY, "egg", "egg yolk", "egg white", "milk", "yoghurt", "yogurt", "curd", "cheese", "cheddar", "mozzarella", "parmesan", "paneer", "butter", "cream", "sour cream", "heavy cream", "cooking cream", "ghee", "labneh", "laban", "buttermilk", "cheese slice")
            put(BAKERY, "bread", "bun", "burger bun", "pav", "roti", "naan", "tortilla", "pita", "croissant", "pizza bread", "bread crumb")
            put(DRY, "rice", "basmati", "dal", "lentil", "chickpea", "chana", "rajma", "bean", "kidney bean", "flour", "atta", "maida", "rava", "semolina", "oat", "pasta", "spaghetti", "penne", "macaroni", "tortellini", "noodle", "hakka noodle", "maggi noodle", "poha", "quinoa", "cashew", "almond", "peanut", "cornflake", "corn flour", "corn starch", "sugar", "brown sugar", "jaggery")
            put(SPICES, "salt", "pink salt", "flaky salt", "turmeric", "cumin", "garam masala", "cardamom", "clove", "cinnamon", "cinnamon stick", "bay leaf", "pepper", "black pepper", "white pepper", "peppercorn", "paprika", "smoked paprika", "saffron", "fenugreek", "kasuri methi", "asafoetida", "hing", "nutmeg", "star anise", "cayenne", "oregano", "dried herb", "italian herb", "dry red chilli", "whole spice", "whole garam masala", "italian seasoning", "cajun seasoning", "msg", "maggi cube", "maggi masala", "stock cube")
            put(PANTRY, "oil", "olive oil", "mustard oil", "sesame oil", "vinegar", "sauce", "soy sauce", "oyster sauce", "fish sauce", "hoisin sauce", "sriracha", "ketchup", "mayonnaise", "mayo", "garlic mayo", "mustard", "honey", "jam", "pickle", "tamarind", "salsa", "pesto", "peanut butter", "coconut milk", "coconut cream", "tomato paste", "tomato puree", "paste", "curry paste", "ginger garlic paste", "stock", "chicken stock", "tinned", "canned tuna", "tuna", "sesame dressing", "kecap manis", "shrimp paste", "apple sauce", "bbq sauce", "sweet chilli sauce", "worcestershire sauce")
            put(BAKING, "baking powder", "baking soda", "cocoa", "cocoa powder", "chocolate", "chocolate chip", "vanilla", "vanilla essence", "vanilla extract", "icing sugar", "yeast")
            put(PRODUCE, "lime juice", "lemon juice", "lemon zest")
            put(DRINKS, "juice", "orange juice", "cranberry juice", "pineapple juice", "grape juice", "sparkling water", "sparkling juice", "soda", "soda water", "sprite", "pepsi", "ginger ale", "ginger beer", "coconut water")
            put(FROZEN, "frozen", "frozen vegetable", "frozen pea", "ice cream", "vanilla ice cream", "puff pastry")
            put(PRODUCE, "onion", "tomato", "cherry tomato", "potato", "garlic", "ginger", "green chilli", "chilli", "chili", "red chilli", "carrot", "capsicum", "bell pepper", "lemon", "lime", "cucumber", "spinach", "paalak", "cabbage", "cauliflower", "broccoli", "brinjal", "aubergine", "eggplant", "okra", "bhindi", "french bean", "green bean", "pea", "green pea", "mushroom", "lettuce", "salad leaf", "apple", "banana", "mango", "coconut", "fresh coconut", "avocado", "zucchini", "pumpkin", "sprout", "corn", "bok choy", "shallot", "passion fruit", "pomegranate", "strawberry", "blueberry", "vegetable", "mixed vegetable", "coleslaw")
        }

        private val normalised: Map<String, Aisle> by lazy { keywords.mapKeys { Names.words(it.key) } }

        private val spiceForms = Regex("\\b(powder|flake|flakes|seed|seeds|masala|seasoning|dried|whole garam)\\b")

        /**
         * Best guess from the name, by the longest whole-word match ("coconut milk" beats
         * "coconut", "green beans" beats "beans"). Ground spices, seeds and flakes go to Spices
         * first, except baking and cocoa powders. The person can move an item and Mise remembers.
         */
        fun guess(name: String): Aisle {
            val n = Names.keyOf(name)
            val words = " $n "
            if (spiceForms.containsMatchIn(n) && !n.contains("baking") && !n.contains("cocoa") && !n.contains("cornflake") && !n.contains("custard")) {
                return SPICES
            }
            var best: Pair<String, Aisle>? = null
            for ((k, a) in normalised) {
                if (words.contains(" $k ") && (best == null || k.length > best.first.length)) best = k to a
            }
            return best?.second ?: OTHER
        }
    }
}

/** Ingredient names: one key for "Onion", "Onions" and "onions (red)", plus common synonyms. */
object Names {
    private val aliases = mapOf(
        "garlic clove" to "garlic", "heavy cream" to "cream", "cooking cream" to "cream", "fresh cream" to "cream",
        "corn starch" to "corn flour", "cornflour" to "corn flour", "black pepper" to "pepper", "pepper powder" to "pepper",
        "dried red chilli" to "dry red chilli", "red chilli flake" to "chilli flake", "vanilla extract" to "vanilla", "vanilla essence" to "vanilla",
        "egg yolk" to "egg", "egg white" to "egg", "curd" to "yoghurt", "yogurt" to "yoghurt", "coriander leaf" to "coriander", "cilantro" to "coriander",
        "bell pepper" to "capsicum", "green onion" to "spring onion", "mayo" to "mayonnaise", "spaghetti pasta" to "spaghetti",
        "chilli" to "green chilli", "chillie" to "green chilli", "jeera" to "cumin seed", "cumin seed" to "cumin seed", "kasoori methi" to "kasuri methi",
        "fenugreek leaf" to "kasuri methi", "paalak" to "spinach", "spinach (paalak)" to "spinach", "mom's masala" to "mom's bottle masala",
        "bottle masala" to "mom's bottle masala", "mom's bottle masala powder" to "mom's bottle masala", "soy sauce kikkoman" to "soy sauce",
        "minced beef" to "minced meat", "minced meat kheema" to "minced meat", "kheema" to "minced meat", "laban" to "buttermilk",
        "buttermilk laban" to "buttermilk", "chickpea chana" to "chickpea", "poha flattened rice" to "poha", "dal lentil" to "dal",
        "curd yoghurt" to "yoghurt", "spring onion white" to "spring onion", "spinach paalak" to "spinach",
    )

    private val keepS = setOf("peas", "hummus", "couscous", "molasses", "asparagus", "swiss", "chips")

    private fun singular(w: String): String = when {
        w in keepS -> w
        w.endsWith("leaves") -> w.dropLast(6) + "leaf"
        w.endsWith("chillies") || w.endsWith("chilies") -> w.dropLast(2)
        w.endsWith("berries") -> w.dropLast(3) + "y"
        w.endsWith("oes") -> w.dropLast(2)
        w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") -> w.dropLast(2)
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("ss") || w.endsWith("us") || w.length <= 3 -> w
        w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    /** Lower case, brackets opened, punctuation dropped, each word singular. No synonyms. */
    fun words(name: String): String {
        val n = name.lowercase().replace(Regex("\\(([^)]*)\\)"), " $1 ").replace(Regex("[^a-z0-9' ]"), " ")
        return n.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { singular(it) }
    }

    fun keyOf(name: String): String {
        val n = words(name)
        return aliases[n] ?: n
    }

    /** Things a recipe uses that are never bought. */
    val neverBuy = setOf("water", "hot water", "cold water", "warm water", "ice", "ice cube", "mom's bottle masala", "pasta water")

    /** Assumed in stock in an Indian and Goan home kitchen, until marked otherwise. */
    val defaultStaples = setOf(
        "salt", "pink salt", "flaky salt", "sugar", "oil", "olive oil", "ghee", "turmeric", "chilli powder", "red chilli powder",
        "kashmiri chilli powder", "coriander powder", "cumin powder", "garam masala", "pepper", "white pepper", "paprika",
        "smoked paprika", "garlic powder", "onion powder", "cumin seed", "mustard seed", "fennel seed", "coriander seed", "clove",
        "cardamom", "cinnamon stick", "cinnamon", "bay leaf", "peppercorn", "dry red chilli", "chilli flake", "kasuri methi",
        "dried herb", "italian seasoning", "italian herb", "cajun seasoning", "oregano", "maggi cube", "ginger garlic paste",
        "vinegar", "soy sauce", "tomato paste", "rice", "basmati rice", "flour", "corn flour", "baking powder", "baking soda",
        "cayenne pepper", "msg", "sesame oil", "mustard oil", "whole garam masala", "whole spice", "cardamom powder", "nutmeg",
    )
}

/** How things are bought, for the List: counts become weights or bulbs, spoons become packs. */
private data class Buy(
    val gPerPc: Double? = null,
    val gPerCup: Double? = null,
    val mlPerCup: Double? = null,
    val packSize: Double? = null,
    val packUnit: Measure? = null,
    val clovesPerBulb: Int? = null,
)

private val buying = mapOf(
    "garlic" to Buy(clovesPerBulb = 10),
    "onion" to Buy(gPerPc = 150.0), "tomato" to Buy(gPerPc = 120.0), "potato" to Buy(gPerPc = 200.0),
    "ginger" to Buy(gPerPc = 15.0, gPerCup = 96.0), "green chilli" to Buy(gPerPc = 5.0), "carrot" to Buy(gPerPc = 80.0),
    "yoghurt" to Buy(gPerCup = 245.0, packSize = 500.0, packUnit = Measure.G),
    "cream" to Buy(mlPerCup = 240.0, packSize = 200.0, packUnit = Measure.ML),
    "milk" to Buy(mlPerCup = 240.0, packSize = 1000.0, packUnit = Measure.ML),
    "coconut milk" to Buy(mlPerCup = 240.0, packSize = 400.0, packUnit = Measure.ML),
    "coconut cream" to Buy(mlPerCup = 240.0, packSize = 200.0, packUnit = Measure.ML),
    "paneer" to Buy(packSize = 200.0, packUnit = Measure.G), "pasta" to Buy(packSize = 500.0, packUnit = Measure.G),
    "spaghetti" to Buy(packSize = 500.0, packUnit = Measure.G), "penne" to Buy(packSize = 500.0, packUnit = Measure.G),
    "macaroni" to Buy(packSize = 500.0, packUnit = Measure.G), "cheddar" to Buy(packSize = 200.0, packUnit = Measure.G),
    "mozzarella" to Buy(packSize = 200.0, packUnit = Measure.G), "mushroom" to Buy(packSize = 250.0, packUnit = Measure.G),
    "butter" to Buy(gPerCup = 227.0), "chicken stock" to Buy(mlPerCup = 240.0), "stock" to Buy(mlPerCup = 240.0),
    "mayonnaise" to Buy(mlPerCup = 240.0), "honey" to Buy(gPerCup = 340.0),
)

object Grocery {

    fun keyOf(name: String) = Names.keyOf(name)

    private data class Need(val key: String, val name: String, val qty: Double?, val unit: Measure, val dish: String)

    /**
     * Merges every ingredient of every planned dish (scaled, with chosen swaps) into list lines.
     * Leftovers add nothing. [aliases] maps a key to the key it should merge into ("Same as...").
     */
    fun build(entries: List<PlanEntry>, aliases: Map<String, String> = emptyMap()): List<GroceryLine> {
        val needs = entries.filter { !it.leftovers }.flatMap { e ->
            val all = e.recipe.ingredients + e.chosenOptions.flatMap { it.ingredients }
            all.map { i ->
                val (q, u) = Scaling.scale(i.qty, i.unit, e.recipe.baseServings, e.servings, i.name, e.recipe.exact)
                val k = keyOf(i.name)
                Need(aliases[k] ?: k, i.name, q, u, e.recipe.title)
            }
        }.filter { it.key !in Names.neverBuy && it.key.isNotBlank() }
        return needs.groupBy { it.key }.map { (key, list) ->
            val m = merge(key, list.map { it.qty to it.unit })
            GroceryLine(
                key = key,
                name = list.first().name.trim().replaceFirstChar { it.uppercase() },
                amount = m.amount, detail = m.detail,
                dishes = list.map { it.dish }.distinct(),
                measure = m.measure, measureUnit = m.measureUnit,
            )
        }.sortedBy { it.name.lowercase() }
    }

    data class Merged(val amount: String, val detail: String?, val measure: Double, val measureUnit: Measure)

    fun merge(amounts: List<Pair<Double?, Measure>>): String = merge("", amounts).amount

    /** "1.5 kg", "2 bulbs", "500 g + 2", "2 × 200 ml", "to taste". */
    fun merge(key: String, amounts: List<Pair<Double?, Measure>>): Merged {
        val b = buying[key]
        var grams = 0.0; var hasMass = false
        var ml = 0.0; var hasVol = false
        var tsp = 0.0; var hasSpoon = false
        var pcs = 0.0
        var cloves = 0.0
        val counts = linkedMapOf<Measure, Double>()
        var taste = false
        for ((q, u) in amounts) {
            if (u == Measure.TO_TASTE) { taste = true; continue }
            if (q == null) continue
            when (u) {
                Measure.G -> { grams += q; hasMass = true }
                Measure.KG -> { grams += q * 1000; hasMass = true }
                Measure.ML -> { ml += q; hasVol = true }
                Measure.L -> { ml += q * 1000; hasVol = true }
                Measure.TSP, Measure.TBSP, Measure.CUP -> {
                    val cups = when (u) { Measure.TSP -> q / 48; Measure.TBSP -> q / 16; else -> q }
                    when {
                        b?.gPerCup != null -> { grams += cups * b.gPerCup; hasMass = true }
                        b?.mlPerCup != null -> { ml += cups * b.mlPerCup; hasVol = true }
                        else -> { tsp += cups * 48; hasSpoon = true }
                    }
                }
                Measure.PC, Measure.NONE -> if (b?.gPerPc != null) pcs += q else counts[Measure.PC] = (counts[Measure.PC] ?: 0.0) + q
                Measure.CLOVE -> if (b?.clovesPerBulb != null) cloves += q else counts[u] = (counts[u] ?: 0.0) + q
                else -> counts[u] = (counts[u] ?: 0.0) + q
            }
        }
        val parts = mutableListOf<String>()
        var detail: String? = null
        var measure = 0.0
        var measureUnit = Measure.NONE

        // Garlic and the like: bulbs to buy, cloves to cook.
        if (cloves > 0 && b?.clovesPerBulb != null) {
            val bulbs = ceil(cloves / b.clovesPerBulb).toInt().coerceAtLeast(1)
            parts += if (bulbs == 1) "1 bulb" else "$bulbs bulbs"
            detail = Scaling.fraction(Scaling.round(cloves, Measure.PC)) + " cloves"
            measure = cloves; measureUnit = Measure.CLOVE
        }
        // Produce bought by weight: show the count with the weight it comes to.
        if (pcs > 0 && b?.gPerPc != null) {
            if (hasMass) {
                grams += pcs * b.gPerPc
            } else {
                val n = Scaling.round(pcs, Measure.PC)
                parts += Scaling.fraction(n)
                val (w, wu) = Scaling.normalise(pcs * b.gPerPc, Measure.G)
                detail = "about " + Scaling.format(w, wu)
                if (measureUnit == Measure.NONE) { measure = pcs; measureUnit = Measure.PC }
            }
        }
        if (hasMass) {
            val (v, u) = Scaling.normalise(grams, Measure.G)
            parts += packed(grams, Measure.G, b) ?: Scaling.format(v, u)
            if (b?.packSize != null && b.packUnit == Measure.G) detail = Scaling.format(v, u)
            if (measureUnit == Measure.NONE) { measure = grams; measureUnit = Measure.G }
        }
        if (hasVol) {
            val (v, u) = Scaling.normalise(ml, Measure.ML)
            parts += packed(ml, Measure.ML, b) ?: Scaling.format(v, u)
            if (b?.packSize != null && b.packUnit == Measure.ML) detail = Scaling.format(v, u)
            if (measureUnit == Measure.NONE) { measure = ml; measureUnit = Measure.ML }
        }
        if (hasSpoon) {
            parts += when {
                tsp >= 48 -> Scaling.format(tsp / 48, Measure.CUP)
                tsp >= 3 -> Scaling.format(tsp / 3, Measure.TBSP)
                else -> Scaling.format(tsp, Measure.TSP)
            }
            if (measureUnit == Measure.NONE) { measure = tsp; measureUnit = Measure.TSP }
        }
        counts.forEach { (u, q) ->
            // A bunch is bought whole.
            val shown = if (u == Measure.BUNCH) ceil(q - 0.01) else q
            parts += Scaling.format(shown, u)
            if (measureUnit == Measure.NONE) { measure = q; measureUnit = u }
        }
        if (parts.isEmpty() && taste) parts += "to taste"
        return Merged(parts.joinToString(" + "), detail, measure, measureUnit)
    }

    private fun packed(total: Double, unit: Measure, b: Buy?): String? {
        val size = b?.packSize ?: return null
        if (b.packUnit != unit) return null
        val n = ceil(total / size - 0.05).toInt().coerceAtLeast(1)
        val (sv, su) = Scaling.normalise(size, unit)
        val label = Scaling.format(sv, su)
        return if (n == 1) "1 × $label" else "$n × $label"
    }

    /** Monday by default; the week start is a setting. */
    fun weekStartOf(date: LocalDate, start: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
        val diff = (date.dayOfWeek.value - start.value + 7) % 7
        return date.minusDays(diff.toLong())
    }
}
