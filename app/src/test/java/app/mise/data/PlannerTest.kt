package app.mise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The on-phone planner follows the household pattern, whatever the seed. */
class PlannerTest {
    private var nextId = 1L
    private fun r(title: String, type: String, vararg ings: String, slots: List<Slot> = emptyList()) =
        Recipe(nextId++, title, "Indian", type, emptyList(), 4, null, null, "", ings.map { Ingredient(it, 1.0, Measure.G, "") }, slots, emptyList())

    private val library = listOf(
        r("Butter chicken", "Curry", "Chicken", "Chicken stock"),
        r("Mutton curry", "Curry", "Mutton"),
        r("Prawn curry", "Curry", "Prawns"),
        r("Dal", "Dal", "Dal (lentils)", "Mustard seeds"),
        r("Paneer butter masala", "Curry", "Paneer"),
        r("Spaghetti bolognese", "Pasta", "Spaghetti", "Minced beef"),
        r("Mushroom pasta", "Pasta", "Pasta", "Mushrooms"),
        r("Nasi goreng", "Rice", "Cooked rice", "Fish sauce", slots = listOf(Slot("Protein", listOf(SlotOption("Chicken", true, "", listOf(Ingredient("Chicken", 1.0, Measure.G, ""))), SlotOption("Shrimp", false, "", listOf(Ingredient("Shrimp", 1.0, Measure.G, ""))))))),
        r("Sanji fried rice", "Rice", "Cooked rice", "Corned beef"),
        r("Tandoori salmon bake", "Dry and roast", "Salmon"),
        r("Beijing beef", "Stir fry", "Beef"),
        r("Jeera rice", "Rice", "Rice", "Cumin seeds"),
        r("Pulao", "Rice", "Rice", "Frozen vegetables"),
        r("Mango lassi", "Drink", "Mango"),
        r("Tuna sandwich", "Sandwich and burger", "Tuna"),
    )
    private val summaries = library.map { RecipeSummary(it.id, it.title, it.cuisine, it.dishType, 4, it.slots.size, it.ingredients.size, false, "", null) }
    private val sunday = LocalDate.of(2026, 11, 8)
    private val week = (0..6).map { sunday.plusDays(it.toLong()) }.filter { it.dayOfWeek.name != "THURSDAY" }.associateWith { listOf(Meal.DINNER) }

    private fun plan(seed: Long, wishes: String = "") =
        Planner.suggest(Planner.Input(library, summaries, emptyList(), emptyList(), week, setOf("THURSDAY:dinner"), 8, wishes, emptySet(), seed))

    private fun kind(s: Suggestion) = Planner.kindOf(library.first { it.id == s.recipe.id })

    @Test fun sidesAndKinds() {
        assertEquals(listOf("Jeera rice", "Pulao"), library.filter { Planner.isSide(it) }.map { it.title })
        assertEquals(null, Planner.kindOf(library.first { it.title == "Mango lassi" }))
        assertEquals(Planner.Protein.BEEF, Planner.proteinsOf(library.first { it.title == "Spaghetti bolognese" }).first())
        assertEquals(Planner.Protein.VEG, Planner.proteinsOf(library.first { it.title == "Dal" }).first())
    }

    @Test fun everyWeekFollowsThePattern() {
        repeat(40) { seed ->
            val items = plan(seed.toLong()).items
            assertEquals(6, items.size)
            assertEquals(6, items.map { it.recipe.id }.toSet().size)
            assertEquals(1, items.count { kind(it) == Planner.Kind.PASTA })
            assertEquals(1, items.count { kind(it) == Planner.Kind.RICE })
            val proteins = items.map { s -> Planner.proteinsOf(library.first { it.id == s.recipe.id }, s.choices.ifEmpty { Choices.decode("", library.first { it.id == s.recipe.id }) }).first() }
            assertTrue(proteins.count { it == Planner.Protein.VEG } <= 1)
            items.filter { kind(it) == Planner.Kind.CURRY }.forEach { assertTrue(it.side != null) }
            assertTrue(items.none { it.recipe.title == "Tuna sandwich" })
        }
    }

    @Test fun wednesdayCoversThursdayAndFridayLunch() {
        val wed = plan(7).items.first { it.date.dayOfWeek.name == "WEDNESDAY" }
        assertEquals(listOf(sunday.plusDays(4) to Meal.LUNCH, sunday.plusDays(5) to Meal.LUNCH), wed.covers)
        assertEquals(12, wed.servings)
    }

    @Test fun wishesAreFollowed() {
        repeat(10) { seed ->
            val items = plan(seed.toLong(), "no beef, fish once").items
            assertTrue(items.none { it.recipe.title == "Spaghetti bolognese" || it.recipe.title == "Beijing beef" || it.recipe.title == "Sanji fried rice" })
            assertTrue(items.any { it.recipe.title == "Tandoori salmon bake" })
        }
    }
}
