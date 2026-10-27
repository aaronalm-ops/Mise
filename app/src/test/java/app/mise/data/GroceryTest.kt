package app.mise.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GroceryTest {
    private fun ing(n: String, q: Double?, u: Measure) = Ingredient(n, q, u, "")
    private fun recipe(title: String, base: Int, ings: List<Ingredient>, slots: List<Slot> = emptyList()) =
        Recipe(1, title, "", emptyList(), base, null, null, "", ings, slots, emptyList())
    private fun entry(r: Recipe, serv: Int, choices: List<Int> = emptyList()) =
        PlanEntry(1, LocalDate.of(2026, 10, 26), Meal.DINNER, r, serv, choices)

    @Test fun mergesOnionAcrossDishes() {
        val a = recipe("Biryani", 4, listOf(ing("Onion", 3.0, Measure.PC)))
        val b = recipe("Dal", 4, listOf(ing("onion", 2.0, Measure.PC)))
        val c = recipe("Fish curry", 4, listOf(ing("Onion ", 2.0, Measure.PC)))
        val lines = Grocery.build(listOf(entry(a, 4), entry(b, 4), entry(c, 4)))
        assertEquals(1, lines.size)
        assertEquals("7 pc", lines[0].amount)
        assertEquals(listOf("Biryani", "Dal", "Fish curry"), lines[0].dishes)
    }

    @Test fun gramsAndKilosAdd() = assertEquals("1.5 kg", Grocery.merge(listOf(500.0 to Measure.G, 1.0 to Measure.KG)))
    @Test fun mixedFamiliesJoin() = assertEquals("500 g + 2 pc", Grocery.merge(listOf(500.0 to Measure.G, 2.0 to Measure.PC)))
    @Test fun spoonsCombine() = assertEquals("2 tbsp", Grocery.merge(listOf(1.0 to Measure.TBSP, 3.0 to Measure.TSP)))
    @Test fun tasteOnly() = assertEquals("to taste", Grocery.merge(listOf(null to Measure.TO_TASTE)))

    @Test fun usesChosenSwapAndServings() {
        val r = recipe(
            "Biryani", 4, listOf(ing("Rice", 500.0, Measure.G)),
            listOf(Slot("Protein", listOf(SlotOption("Chicken", true, "", listOf(ing("Chicken", 1.0, Measure.KG))), SlotOption("Prawn", false, "", listOf(ing("Prawn", 600.0, Measure.G)))))),
        )
        val lines = Grocery.build(listOf(entry(r, 6, listOf(1))))
        assertEquals(listOf("Prawn" to "900 g", "Rice" to "750 g"), lines.map { it.name to it.amount })
    }

    @Test fun guessesAisles() {
        assertEquals(Aisle.PRODUCE, Aisle.guess("Onion"))
        assertEquals(Aisle.MEAT, Aisle.guess("Chicken, bone-in"))
        assertEquals(Aisle.DRY, Aisle.guess("Basmati rice"))
        assertEquals(Aisle.HERBS, Aisle.guess("Mint"))
    }

    @Test fun weekStartsMonday() = assertEquals(LocalDate.of(2026, 10, 26), Grocery.weekStartOf(LocalDate.of(2026, 11, 1)))
}
