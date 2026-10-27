package app.mise.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Units a recipe can use. Mass and volume units convert within their family when a scaled
 * quantity crosses a boundary (1000 g shows as 1 kg). Count-like units round to halves;
 * spoon and cup measures round to quarters and show as fractions.
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
    PINCH("pinch", "pinch", Kind.WHOLE),
    BUNCH("bunch", "bunch", Kind.WHOLE),
    TO_TASTE("to taste", "to taste", Kind.NONE);

    enum class Kind { MASS, VOLUME, SPOON, COUNT, WHOLE, NONE }

    companion object {
        fun from(key: String?): Measure = entries.firstOrNull { it.key == key } ?: G
    }
}

/** A quantity scaled and ready to show, e.g. "1.5 kg", "1½ tbsp", "to taste". */
object Scaling {

    fun scale(qty: Double?, unit: Measure, baseServings: Int, servings: Int): Pair<Double?, Measure> {
        if (qty == null || unit == Measure.TO_TASTE) return null to unit
        val factor = servings.toDouble() / baseServings.coerceAtLeast(1)
        return normalise(qty * factor, unit)
    }

    /** Moves between g and kg, ml and l, so 1500 g reads 1.5 kg and 0.75 kg reads 750 g. */
    fun normalise(value: Double, unit: Measure): Pair<Double, Measure> = when {
        unit == Measure.G && value >= 1000 -> value / 1000 to Measure.KG
        unit == Measure.KG && value < 1 -> value * 1000 to Measure.G
        unit == Measure.ML && value >= 1000 -> value / 1000 to Measure.L
        unit == Measure.L && value < 1 -> value * 1000 to Measure.ML
        else -> value to unit
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
        Measure.Kind.SPOON -> ((value * 4).roundToLong() / 4.0).coerceAtLeast(0.25)
        Measure.Kind.COUNT -> ((value * 2).roundToLong() / 2.0).coerceAtLeast(0.5)
        Measure.Kind.WHOLE -> value.roundToLong().coerceAtLeast(1).toDouble()
        Measure.Kind.NONE -> value
    }

    /** "750 g", "1.5 kg", "1½ tbsp", "4½ pc", "to taste". */
    fun format(qty: Double?, unit: Measure): String {
        if (qty == null || unit == Measure.TO_TASTE) return if (unit == Measure.TO_TASTE) "to taste" else ""
        val v = round(qty, unit)
        val number = when (unit.kind) {
            Measure.Kind.SPOON, Measure.Kind.COUNT -> fraction(v)
            else -> plain(v)
        }
        return "$number ${unit.label}"
    }

    fun scaledText(qty: Double?, unit: Measure, baseServings: Int, servings: Int): String {
        val (v, u) = scale(qty, unit, baseServings, servings)
        return format(v, u)
    }

    fun plain(v: Double): String =
        if (abs(v - v.roundToLong()) < 1e-9) v.roundToLong().toString()
        else v.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun fraction(v: Double): String {
        val whole = floor(v).toLong()
        val part = v - whole
        val glyph = when {
            abs(part - 0.25) < 1e-6 -> "¼"
            abs(part - 0.5) < 1e-6 -> "½"
            abs(part - 0.75) < 1e-6 -> "¾"
            else -> ""
        }
        return when {
            glyph.isEmpty() -> whole.toString()
            whole == 0L -> glyph
            else -> "$whole$glyph"
        }
    }

    /** Reads what someone typed: "1.5", "1,5", "1/2", "1 1/2", "½". Blank or unreadable is null. */
    fun parse(text: String): Double? {
        val t = text.trim().replace(',', '.')
            .replace("¼", " 1/4").replace("½", " 1/2").replace("¾", " 3/4").trim()
        if (t.isEmpty()) return null
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
}
