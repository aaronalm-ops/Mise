package app.mise.data

import java.time.LocalDate

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
    val choices: List<Int>,
) {
    /** The chosen option per swap (falls back to each swap's default). */
    val chosenOptions: List<SlotOption>
        get() = recipe.slots.mapIndexedNotNull { i, s ->
            s.options.getOrNull(choices.getOrNull(i) ?: -1) ?: s.options.firstOrNull { it.isDefault } ?: s.options.firstOrNull()
        }
}

/** One merged line of the grocery list. */
data class GroceryLine(
    val key: String,
    val name: String,
    val amount: String,
    val dishes: List<String>,
)

/** Supermarket sections, in walking order. */
enum class Aisle(val key: String, val label: String) {
    PRODUCE("produce", "Fruit and veg"),
    HERBS("herbs", "Herbs"),
    MEAT("meat", "Meat and fish"),
    DAIRY("dairy", "Dairy and eggs"),
    BAKERY("bakery", "Bakery"),
    DRY("dry", "Rice, grains and pulses"),
    SPICES("spices", "Spices and masalas"),
    PANTRY("pantry", "Oils, sauces and tins"),
    FROZEN("frozen", "Frozen"),
    OTHER("other", "Other");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key }

        private val words: List<Pair<Aisle, List<String>>> = listOf(
            HERBS to listOf("coriander", "cilantro", "mint", "parsley", "basil", "curry leaves", "dill", "thyme", "rosemary", "spring onion", "methi leaves"),
            PANTRY to listOf("oil", "vinegar", "sauce", "paste", "ketchup", "coconut milk", "tin", "can", "stock", "honey", "jam", "pickle", "soy", "tamarind"),
            MEAT to listOf("chicken", "mutton", "lamb", "beef", "pork", "prawn", "shrimp", "fish", "kingfish", "salmon", "tuna", "crab", "squid", "mince", "sausage", "bacon", "egg"),
            DAIRY to listOf("milk", "yoghurt", "yogurt", "curd", "cheese", "paneer", "butter", "cream", "ghee", "labneh"),
            BAKERY to listOf("bread", "bun", "pav", "roti", "naan", "tortilla", "pita", "croissant"),
            FROZEN to listOf("frozen", "peas frozen", "ice cream"),
            SPICES to listOf("masala", "turmeric", "chilli powder", "chili powder", "cumin", "coriander powder", "garam", "cardamom", "clove", "cinnamon", "bay leaf", "pepper", "paprika", "saffron", "mustard seed", "fenugreek", "asafoetida", "hing", "salt", "spice", "nutmeg", "star anise"),
            DRY to listOf("rice", "basmati", "dal", "lentil", "chickpea", "chana", "rajma", "bean", "flour", "atta", "maida", "rava", "semolina", "oats", "pasta", "noodle", "poha", "sugar", "jaggery", "quinoa"),
            PRODUCE to listOf("onion", "tomato", "potato", "garlic", "ginger", "chilli", "chili", "carrot", "capsicum", "pepper", "lemon", "lime", "cucumber", "spinach", "cabbage", "cauliflower", "broccoli", "brinjal", "aubergine", "okra", "bhindi", "beans", "peas", "mushroom", "lettuce", "apple", "banana", "mango", "coconut", "avocado", "zucchini", "pumpkin", "sprout", "corn"),
        )

        /** Best guess from the name; the person can move an item and Mise remembers. */
        fun guess(name: String): Aisle {
            val n = name.lowercase()
            for ((aisle, list) in words) if (list.any { n.contains(it) }) return aisle
            return OTHER
        }
    }
}

object Grocery {

    fun keyOf(name: String) = name.trim().replace(Regex("\\s+"), " ").lowercase()

    private data class Need(val key: String, val name: String, val qty: Double?, val unit: Measure, val dish: String)

    /** Merges every ingredient of every planned dish (scaled, with chosen swaps) into list lines. */
    fun build(entries: List<PlanEntry>): List<GroceryLine> {
        val needs = entries.flatMap { e ->
            val all = e.recipe.ingredients + e.chosenOptions.flatMap { it.ingredients }
            all.map { i ->
                val (q, u) = Scaling.scale(i.qty, i.unit, e.recipe.baseServings, e.servings)
                Need(keyOf(i.name), i.name, q, u, e.recipe.title)
            }
        }
        return needs.groupBy { it.key }.map { (key, list) ->
            GroceryLine(
                key = key,
                name = list.first().name,
                amount = merge(list.map { it.qty to it.unit }),
                dishes = list.map { it.dish }.distinct(),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** "1.5 kg", "7 pc", "500 g + 2 pc", "to taste". */
    fun merge(amounts: List<Pair<Double?, Measure>>): String {
        var grams = 0.0; var hasMass = false
        var ml = 0.0; var hasVol = false
        var tsp = 0.0; var hasSpoon = false
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
                Measure.TSP -> { tsp += q; hasSpoon = true }
                Measure.TBSP -> { tsp += q * 3; hasSpoon = true }
                Measure.CUP -> { tsp += q * 48; hasSpoon = true }
                else -> counts[u] = (counts[u] ?: 0.0) + q
            }
        }
        val parts = mutableListOf<String>()
        if (hasMass) Scaling.normalise(grams, Measure.G).let { (v, u) -> parts += Scaling.format(v, u) }
        if (hasVol) Scaling.normalise(ml, Measure.ML).let { (v, u) -> parts += Scaling.format(v, u) }
        if (hasSpoon) parts += when {
            tsp >= 48 -> Scaling.format(tsp / 48, Measure.CUP)
            tsp >= 3 -> Scaling.format(tsp / 3, Measure.TBSP)
            else -> Scaling.format(tsp, Measure.TSP)
        }
        counts.forEach { (u, q) -> parts += Scaling.format(q, u) }
        if (parts.isEmpty() && taste) parts += "to taste"
        return parts.joinToString(" + ")
    }

    fun weekStartOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
}
