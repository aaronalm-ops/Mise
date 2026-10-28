package app.mise.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Units a recipe can use. Mass and volume convert within their family when a scaled amount
 * crosses a boundary (1000 g shows as 1 kg). NONE means "no unit chosen yet".
 */
enum class Measure(val key: String, val label: String, val kind: Kind) {
    G("g", "g", Kind.MASS),
    KG("kg", "kg", Kind.MASS),
    ML("ml", "ml", Kind.VOLUME),
    L("l", "l", Kind.VOLUME),
    TSP("tsp", "tsp", Kind.SPOON),
    TBSP("tbsp", "tbsp", Kind.SPOON),
    CUP("cup", "cup", Kind.SPOON),
    PC("pc", "pc", Kind.COUNT),
    CLOVE("clove", "clove", Kind.COUNT),
    CAN("can", "can", Kind.COUNT),
    PACK("pack", "pack", Kind.COUNT),
    BUNCH("bunch", "bunch", Kind.PART),
    SPRIG("sprig", "sprig", Kind.WHOLE),
    PINCH("pinch", "pinch", Kind.WHOLE),
    TO_TASTE("to taste", "to taste", Kind.NONE),
    NONE("", "unit", Kind.COUNT);

    enum class Kind { MASS, VOLUME, SPOON, COUNT, PART, WHOLE, NONE }

    companion object {
        /** The units offered in pickers (NONE is only a placeholder). */
        val choices = entries.filter { it != NONE }
        fun from(key: String?): Measure = entries.firstOrNull { it.key == key } ?: if (key.isNullOrEmpty()) NONE else G

        private val words = mapOf(
            "g" to G, "gm" to G, "gms" to G, "gram" to G, "grams" to G, "gr" to G,
            "kg" to KG, "kgs" to KG, "kilo" to KG, "kilos" to KG, "kilogram" to KG, "kilograms" to KG,
            "ml" to ML, "millilitre" to ML, "millilitres" to ML, "milliliter" to ML, "milliliters" to ML,
            "l" to L, "litre" to L, "litres" to L, "liter" to L, "liters" to L, "ltr" to L,
            "tsp" to TSP, "teaspoon" to TSP, "teaspoons" to TSP, "t" to TSP,
            "tbsp" to TBSP, "tablespoon" to TBSP, "tablespoons" to TBSP, "tbs" to TBSP, "T" to TBSP,
            "cup" to CUP, "cups" to CUP, "pc" to PC, "pcs" to PC, "piece" to PC, "pieces" to PC,
            "clove" to CLOVE, "cloves" to CLOVE, "can" to CAN, "cans" to CAN, "tin" to CAN, "tins" to CAN,
            "pack" to PACK, "packs" to PACK, "packet" to PACK, "packets" to PACK,
            "bunch" to BUNCH, "bunches" to BUNCH, "sprig" to SPRIG, "sprigs" to SPRIG, "pinch" to PINCH, "pinches" to PINCH,
        )
        fun fromWord(w: String): Measure? = words[w] ?: words[w.lowercase()]
    }
}

/** How an ingredient grows when a recipe is cooked for more or fewer people. */
enum class ScaleClass { LINEAR, SEASONING, WHOLE_SPICE, FAT, LIQUID, EGG }

object Scaling {

    private val wholeSpice = listOf("cardamom", "cinnamon stick", "bay leaf", "bay leaves", "peppercorn", "star anise", "dry red chilli", "dried red chilli", "whole garam masala", "whole spice")
    private val seasoning = listOf("salt", "powder", "masala", "paste", "sauce", "vinegar", "chilli", "chili", "garlic", "ginger", "pepper", "paprika", "cumin", "turmeric", "seasoning", "herbs", "kasuri methi", "msg", "flakes", "seeds", "mustard", "cayenne", "oregano", "sriracha")
    private val fat = listOf("oil", "ghee", "butter")
    private val liquid = listOf("water", "stock", "broth")

    fun classify(name: String, unit: Measure): ScaleClass {
        val n = name.lowercase()
        return when {
            n == "egg" || n == "eggs" || n.startsWith("egg ") -> ScaleClass.EGG
            unit != Measure.CLOVE && (n == "cloves" || n == "clove") -> ScaleClass.WHOLE_SPICE
            wholeSpice.any { n.contains(it) } -> ScaleClass.WHOLE_SPICE
            liquid.any { n.contains(it) } && !n.contains("coconut") -> ScaleClass.LIQUID
            fat.any { Regex("\\b$it\\b").containsMatchIn(n) } && !n.contains("peanut") -> ScaleClass.FAT
            seasoning.any { n.contains(it) } -> ScaleClass.SEASONING
            else -> ScaleClass.LINEAR
        }
    }

    /**
     * Scales one ingredient. Most things scale in a straight line; seasoning, whole spices and
     * frying fat grow more slowly (doubling a curry does not double the cloves), cooking liquid a
     * little slower, eggs stay whole. Baking, desserts and drinks always scale exactly.
     */
    fun scale(qty: Double?, unit: Measure, baseServings: Int, servings: Int, name: String = "", exact: Boolean = false): Pair<Double?, Measure> {
        if (qty == null || unit == Measure.TO_TASTE) return null to unit
        val f = servings.toDouble() / baseServings.coerceAtLeast(1)
        if (f == 1.0) return normalise(qty, unit)
        val cls = if (exact) ScaleClass.LINEAR else classify(name, unit)
        val v = when (cls) {
            ScaleClass.LINEAR -> qty * f
            ScaleClass.SEASONING -> qty * f.pow(0.8)
            ScaleClass.FAT -> qty * f.pow(0.6)
            ScaleClass.WHOLE_SPICE -> {
                val raw = (qty * f.pow(0.6)).roundToLong().coerceAtLeast(1).toDouble()
                val n = name.lowercase()
                if (n.contains("bay leaf") || n.contains("bay leaves") || n.contains("cinnamon")) raw.coerceAtMost(maxOf(3.0, qty)) else raw
            }
            ScaleClass.LIQUID -> if (f > 1) qty * f.pow(0.85) else qty * f
            ScaleClass.EGG -> if (unit.kind == Measure.Kind.COUNT || unit == Measure.NONE) ceil(qty * f - 0.25).coerceAtLeast(1.0) else qty * f
        }
        return normalise(v, unit)
    }

    /** Moves between g and kg, ml and l, and shows tiny cup amounts in tbsp. */
    fun normalise(value: Double, unit: Measure): Pair<Double, Measure> = when {
        unit == Measure.G && value >= 1000 -> value / 1000 to Measure.KG
        unit == Measure.KG && value < 1 -> value * 1000 to Measure.G
        unit == Measure.ML && value >= 1000 -> value / 1000 to Measure.L
        unit == Measure.L && value < 1 -> value * 1000 to Measure.ML
        unit == Measure.CUP && value < 0.25 -> value * 16 to Measure.TBSP
        unit == Measure.TBSP && value < 0.5 -> value * 3 to Measure.TSP
        else -> value to unit
    }

    private val spoonParts = listOf(0.0, 0.125, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 1.0)
    private val cupParts = listOf(0.0, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 1.0)

    private fun nearestPart(v: Double, parts: List<Double>): Double {
        val w = floor(v)
        val frac = v - w
        return w + parts.minBy { abs(it - frac) }
    }

    fun round(value: Double, unit: Measure): Double = when (unit.kind) {
        Measure.Kind.MASS, Measure.Kind.VOLUME -> when (unit) {
            Measure.KG, Measure.L -> (value * 100).roundToLong() / 100.0
            else -> when {
                value < 10 -> (value * 10).roundToLong() / 10.0
                value < 100 -> value.roundToLong().toDouble()
                else -> (value / 5).roundToLong() * 5.0
            }
        }
        Measure.Kind.SPOON -> when (unit) {
            Measure.TSP -> nearestPart(value, spoonParts).coerceAtLeast(0.125)
            else -> nearestPart(value, cupParts).coerceAtLeast(0.25)
        }
        Measure.Kind.COUNT -> ((value * 2).roundToLong() / 2.0).coerceAtLeast(0.5)
        Measure.Kind.PART -> ((value * 4).roundToLong() / 4.0).coerceAtLeast(0.25)
        Measure.Kind.WHOLE -> value.roundToLong().coerceAtLeast(1).toDouble()
        Measure.Kind.NONE -> value
    }

    /** "750 g", "1.5 kg", "1½ tbsp", "⅛ tsp", "4½ pc", "¼ bunch", "to taste". */
    fun format(qty: Double?, unit: Measure): String {
        if (unit == Measure.TO_TASTE) return "to taste"
        if (qty == null) return ""
        if (unit == Measure.TSP && qty < 0.1) return "1 pinch"
        val v = round(qty, unit)
        val number = when (unit.kind) {
            Measure.Kind.SPOON, Measure.Kind.COUNT, Measure.Kind.PART -> fraction(v)
            else -> plain(v)
        }
        return if (unit == Measure.NONE || unit == Measure.PC) number else "$number ${unit.label}"
    }

    fun scaledText(qty: Double?, unit: Measure, baseServings: Int, servings: Int, name: String = "", exact: Boolean = false): String {
        val (v, u) = scale(qty, unit, baseServings, servings, name, exact)
        return format(v, u)
    }

    fun plain(v: Double): String =
        if (abs(v - v.roundToLong()) < 1e-9) v.roundToLong().toString()
        else v.toBigDecimal().stripTrailingZeros().toPlainString()

    fun fraction(v: Double): String {
        val whole = floor(v + 1e-9).toLong()
        val part = v - whole
        val glyph = when {
            part < 0.02 -> ""
            abs(part - 0.125) < 0.02 -> "⅛"
            abs(part - 0.25) < 0.02 -> "¼"
            abs(part - 1.0 / 3) < 0.02 -> "⅓"
            abs(part - 0.5) < 0.02 -> "½"
            abs(part - 2.0 / 3) < 0.02 -> "⅔"
            abs(part - 0.75) < 0.02 -> "¾"
            else -> return plain((v * 100).roundToLong() / 100.0)
        }
        return when {
            glyph.isEmpty() -> whole.toString()
            whole == 0L -> glyph
            else -> "$whole$glyph"
        }
    }

    /**
     * Reads what someone typed: "1.5", "1,5", "1,000", "1/2", "1 1/2", "½", "1½".
     * Blank is null. Anything else ("1-2", "a handful") is unreadable: [parseOrNull] returns
     * null and [isReadable] says false, so the editor can keep it as a note instead.
     */
    fun parse(text: String): Double? = parseOrNull(text)

    fun isReadable(text: String) = text.isBlank() || parseOrNull(text) != null

    fun parseOrNull(text: String): Double? {
        var t = text.trim()
        if (t.isEmpty()) return null
        t = t.replace(Regex("(\\d),(\\d{3})(?!\\d)"), "$1$2") // 1,000 is a thousand
        t = t.replace(',', '.')
        t = t.replace("⅛", " 1/8").replace("¼", " 1/4").replace("⅓", " 1/3").replace("½", " 1/2")
            .replace("⅔", " 2/3").replace("¾", " 3/4").trim()
        return try {
            t.split(Regex("\\s+")).sumOf { part ->
                if ('/' in part) {
                    val (a, b) = part.split('/', limit = 2)
                    a.toDouble() / b.toDouble()
                } else {
                    part.toDouble()
                }
            }.takeIf { it.isFinite() && it >= 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** "500 g basmati rice, washed" or "2 onions" -> (qty text, unit, name, note). Used by Paste a list. */
    fun parseLine(line: String): ParsedLine? {
        val clean = line.trim().trimStart('-', '•', '*', '·').trim()
        if (clean.isEmpty()) return null
        val m = Regex("^([0-9][0-9.,/ ]*|[⅛¼⅓½⅔¾]|[0-9]+[⅛¼⅓½⅔¾])\\s*([A-Za-z]+)?\\.?\\s+(.*)$").find(clean)
        var qty = ""
        var unit = Measure.NONE
        var rest = clean
        if (m != null) {
            val unitWord = m.groupValues[2]
            val u = if (unitWord.isNotEmpty()) Measure.fromWord(unitWord) else null
            qty = m.groupValues[1].trim()
            if (u != null) { unit = u; rest = m.groupValues[3] } else { rest = (unitWord + " " + m.groupValues[3]).trim() }
        } else if (clean.lowercase().endsWith("to taste")) {
            unit = Measure.TO_TASTE
            rest = clean.dropLast(8).trim().trimEnd(',')
        }
        val name = rest.substringBefore(',').trim().replaceFirstChar { it.uppercase() }
        val note = rest.substringAfter(',', "").trim()
        if (name.isEmpty()) return null
        if (qty.isNotEmpty() && unit == Measure.NONE) unit = Units.guessUnit(name)
        return ParsedLine(qty, unit, name, note)
    }

    data class ParsedLine(val qty: String, val unit: Measure, val name: String, val note: String)
}

object Units {
    private val countable = listOf(
        "onion", "tomato", "potato", "egg", "lemon", "lime", "chilli", "chili", "capsicum", "pepper", "banana", "apple",
        "carrot", "cucumber", "avocado", "mango", "bun", "patty", "patties", "slice", "fillet", "shallot", "orange",
        "cardamom", "bay leaf", "bay leaves", "cinnamon stick", "maggi cube", "cube", "tortilla", "pita", "naan", "roti",
    )

    /** A sensible unit when a quantity is typed with none: pieces for countable things, else grams. */
    fun guessUnit(name: String): Measure {
        val n = name.lowercase()
        return when {
            n.contains("garlic") -> Measure.CLOVE
            countable.any { n.contains(it) } -> Measure.PC
            else -> Measure.G
        }
    }
}
