package app.mise.data

/** A recipe as the screens read it. */
data class Recipe(
    val id: Long,
    val title: String,
    val cuisine: String,
    val tags: List<String>,
    val baseServings: Int,
    val prepMins: Int?,
    val cookMins: Int?,
    val notes: String,
    val ingredients: List<Ingredient>,
    val slots: List<Slot>,
    val steps: List<String>,
)

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
    val baseServings: Int,
    val slotCount: Int,
    val ingredientCount: Int,
    val toCheck: Boolean = false,
)

data class DraftSummary(val id: Long, val title: String, val recipeId: Long?, val updatedAt: Long)
