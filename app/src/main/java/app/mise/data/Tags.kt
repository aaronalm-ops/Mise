package app.mise.data

/** One cuisine and one dish type per recipe; these drive the Recipes filters. */
object Tags {
    val cuisines = listOf(
        "Indian", "Goan", "Pakistani", "Indo-Chinese", "Chinese", "Thai", "Indonesian", "Japanese",
        "Italian", "American", "British", "Turkish", "Fusion", "International",
    )
    val dishTypes = listOf(
        "Curry", "Dal", "Dry and roast", "Rice", "Noodles", "Pasta", "Stir fry", "Soup and stew",
        "Finger food", "Sandwich and burger", "Pizza and savoury bake", "Salad", "Breakfast",
        "Dessert and sweet bake", "Drink", "Sauce and dip",
    )

    /** Dish types cooked by exact measure, where every amount scales in a straight line. */
    val linearTypes = setOf("Dessert and sweet bake", "Drink", "Breakfast", "Pizza and savoury bake")
}
