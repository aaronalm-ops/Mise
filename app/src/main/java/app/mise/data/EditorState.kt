package app.mise.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Stable keys for rows in the editor, so typing never jumps to another row. */
object Keys {
    private val next = AtomicLong(System.nanoTime())
    fun new(): Long = next.incrementAndGet()
}

data class EIng(val key: Long = Keys.new(), val name: String = "", val qty: String = "", val unit: String = Measure.NONE.key, val note: String = "", val showNote: Boolean = false) {
    val isBlank get() = name.isBlank() && qty.isBlank() && note.isBlank()
}

data class EOption(val key: Long = Keys.new(), val label: String = "", val isDefault: Boolean = false, val note: String = "", val ings: List<EIng> = emptyList())

data class ESlot(val key: Long = Keys.new(), val name: String = "", val options: List<EOption> = listOf(EOption(isDefault = true), EOption()))

data class EStep(val key: Long = Keys.new(), val text: String = "")

/** Everything the recipe editor holds. Plain text fields, so half-typed numbers survive. */
data class EditorState(
    val recipeId: Long? = null,
    val title: String = "",
    val cuisine: String = "",
    val dishType: String = "",
    val status: String = RecipeStatus.OK,
    val tags: String = "",
    val serves: Int = 4,
    val prep: String = "",
    val cook: String = "",
    val notes: String = "",
    val ings: List<EIng> = listOf(EIng()),
    val slots: List<ESlot> = emptyList(),
    val steps: List<EStep> = listOf(EStep()),
) {
    val hasContent: Boolean
        get() = title.isNotBlank() || cuisine.isNotBlank() || notes.isNotBlank() ||
            ings.any { !it.isBlank } || slots.isNotEmpty() || steps.any { it.text.isNotBlank() }

    fun toJson(): String = JSONObject().apply {
        put("recipeId", recipeId ?: JSONObject.NULL)
        put("title", title); put("cuisine", cuisine); put("dishType", dishType); put("status", status); put("tags", tags); put("serves", serves)
        put("prep", prep); put("cook", cook); put("notes", notes)
        put("ings", ingsJson(ings))
        put("slots", JSONArray().apply {
            slots.forEach { s ->
                put(JSONObject().apply {
                    put("name", s.name)
                    put("options", JSONArray().apply {
                        s.options.forEach { o ->
                            put(JSONObject().apply {
                                put("label", o.label); put("isDefault", o.isDefault); put("note", o.note)
                                put("ings", ingsJson(o.ings))
                            })
                        }
                    })
                })
            }
        })
        put("steps", JSONArray().apply { steps.forEach { put(it.text) } })
    }.toString()

    companion object {
        private fun ingsJson(list: List<EIng>) = JSONArray().apply {
            list.forEach { i ->
                put(JSONObject().apply { put("name", i.name); put("qty", i.qty); put("unit", i.unit); put("note", i.note) })
            }
        }

        private fun ingsFrom(a: JSONArray?): List<EIng> =
            if (a == null) emptyList() else (0 until a.length()).map { n ->
                val o = a.getJSONObject(n)
                EIng(name = o.optString("name"), qty = o.optString("qty"), unit = o.optString("unit", Measure.G.key), note = o.optString("note"), showNote = o.optString("note").isNotBlank())
            }

        fun fromJson(json: String): EditorState {
            val o = JSONObject(json)
            val slots = o.optJSONArray("slots")?.let { a ->
                (0 until a.length()).map { n ->
                    val s = a.getJSONObject(n)
                    val opts = s.optJSONArray("options")
                    ESlot(
                        name = s.optString("name"),
                        options = if (opts == null) emptyList() else (0 until opts.length()).map { m ->
                            val p = opts.getJSONObject(m)
                            EOption(label = p.optString("label"), isDefault = p.optBoolean("isDefault"), note = p.optString("note"), ings = ingsFrom(p.optJSONArray("ings")))
                        },
                    )
                }
            } ?: emptyList()
            val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { EStep(text = a.getString(it)) } } ?: emptyList()
            return EditorState(
                recipeId = if (o.isNull("recipeId")) null else o.optLong("recipeId"),
                title = o.optString("title"), cuisine = o.optString("cuisine"), dishType = o.optString("dishType"),
                status = o.optString("status", RecipeStatus.OK).ifBlank { RecipeStatus.OK }, tags = o.optString("tags"),
                serves = o.optInt("serves", 4), prep = o.optString("prep"), cook = o.optString("cook"),
                notes = o.optString("notes"),
                ings = ingsFrom(o.optJSONArray("ings")).ifEmpty { listOf(EIng()) },
                slots = slots,
                steps = steps.ifEmpty { listOf(EStep()) },
            )
        }

        fun fromRecipe(r: Recipe): EditorState = EditorState(
            recipeId = r.id,
            title = r.title, cuisine = r.cuisine, dishType = r.dishType, status = r.status, tags = r.tags.joinToString(", "),
            serves = r.baseServings,
            prep = r.prepMins?.toString() ?: "", cook = r.cookMins?.toString() ?: "",
            notes = r.notes,
            ings = r.ingredients.map { it.toEdit() }.ifEmpty { listOf(EIng()) },
            slots = r.slots.map { s ->
                ESlot(name = s.name, options = s.options.map { o -> EOption(label = o.label, isDefault = o.isDefault, note = o.note, ings = o.ingredients.map { it.toEdit() }) })
            },
            steps = r.steps.map { EStep(text = it) }.ifEmpty { listOf(EStep()) },
        )

        private fun Ingredient.toEdit() = EIng(
            name = name,
            qty = qty?.let { Scaling.plain(it) } ?: "",
            unit = unit.key,
            note = note,
            showNote = note.isNotBlank(),
        )
    }
}
