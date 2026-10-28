package app.mise.data

/** A recipe as the screens read it. */
data class Recipe(
    val id: Long,
    val title: String,
    val cuisine: String,
    val dishType: String,
    val tags: List<String>,
    val baseServings: Int,
    val prepMins: Int?,
    val cookMins: Int?,
    val notes: String,
    val ingredients: List<Ingredient>,
    val slots: List<Slot>,
    val steps: List<String>,
    val status: String = RecipeStatus.OK,
    val updatedAt: Long = 0,
) {
    /** Exact-measure dishes (baking, desserts, drinks) scale every amount in a straight line. */
    val exact: Boolean get() = dishType in Tags.linearTypes
}

object RecipeStatus {
    const val OK = "ok"
    const val TO_CHECK = "to_check"
}

data class Ingredient(
    val name: String,
    val qty: Double?,
    val unit: Measure,
    val note: String,
)

/** A swap, e.g. "Protein" with options Chicken, Mutton, Prawn. */
data class Slot(val name: String, val options: List<SlotOption>)

data class SlotOption(
    val label: String,
    val isDefault: Boolean,
    val note: String,
    val ingredients: List<Ingredient>,
)

data class RecipeSummary(
    val id: Long,
    val title: String,
    val cuisine: String,
    val dishType: String,
    val baseServings: Int,
    val slotCount: Int,
    val ingredientCount: Int,
    val toCheck: Boolean,
    val searchText: String,
    val lastPlanned: String?,
)

data class DraftSummary(val id: Long, val title: String, val recipeId: Long?, val updatedAt: Long)

/**
 * Swap choices are stored by option label (joined with this separator), so editing a recipe's
 * swaps never quietly changes what was planned. Older entries stored positions ("0,1").
 */
object Choices {
    const val SEP = "\u001F"

    fun encode(labels: List<String>) = labels.joinToString(SEP)

    fun decode(raw: String, recipe: Recipe): List<String> {
        if (raw.isBlank()) return recipe.slots.map { s -> (s.options.firstOrNull { it.isDefault } ?: s.options.firstOrNull())?.label ?: "" }
        if (!raw.contains(SEP) && raw.matches(Regex("[0-9,]+"))) {
            val idx = raw.split(',').mapNotNull { it.toIntOrNull() }
            return recipe.slots.mapIndexed { i, s -> s.options.getOrNull(idx.getOrNull(i) ?: -1)?.label ?: (s.options.firstOrNull { it.isDefault } ?: s.options.firstOrNull())?.label ?: "" }
        }
        val labels = raw.split(SEP)
        return recipe.slots.mapIndexed { i, s ->
            labels.getOrNull(i)?.takeIf { l -> s.options.any { it.label == l } }
                ?: (s.options.firstOrNull { it.isDefault } ?: s.options.firstOrNull())?.label ?: ""
        }
    }

    fun options(recipe: Recipe, labels: List<String>): List<SlotOption> =
        recipe.slots.mapIndexedNotNull { i, s -> s.options.firstOrNull { it.label == labels.getOrNull(i) } ?: s.options.firstOrNull { it.isDefault } ?: s.options.firstOrNull() }
}
