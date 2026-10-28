package app.mise.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import app.mise.sync.ServerError
import app.mise.sync.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlin.math.max

/** A dish the AI suggests for a slot, already matched to a recipe in the library. */
data class Suggestion(val date: LocalDate, val meal: Meal, val recipe: RecipeSummary, val reason: String)

data class WeekIdeas(val items: List<Suggestion>, val summary: String)

/**
 * The AI helpers. Everything goes through Mise's own server function (supabase/functions/mise-ai),
 * which holds the Claude key; the phone only sends the photos or request and the signed-in token.
 * Each result is a draft you review: nothing is saved or planned without your tap.
 */
object Ai {
    val available: Boolean get() = Supa.configured && Supa.signedIn

    /** Reads up to four photos of one recipe (pages, a card, a screenshot) into a draft. */
    suspend fun recipeFromPhotos(ctx: Context, uris: List<Uri>, hint: String): EditorState = withContext(Dispatchers.IO) {
        val images = JSONArray()
        uris.take(4).forEach { images.put(JSONObject().put("media_type", "image/jpeg").put("data", encode(ctx, it))) }
        val r = Supa.ai(JSONObject().put("task", "photo").put("images", images).put("hint", hint.trim()))
        toEditor(r.getJSONObject("recipe"), "Read from a photo by Mise AI. Check the amounts, then save.")
    }

    /** Writes a recipe for a request ("butter chicken without cream, 30 minutes"). */
    suspend fun recipeFromIdea(prompt: String, serves: Int): EditorState = withContext(Dispatchers.IO) {
        val r = Supa.ai(JSONObject().put("task", "idea").put("prompt", prompt.trim()).put("serves", serves))
        toEditor(r.getJSONObject("recipe"), "Written by Mise AI for: “${prompt.trim()}”. Check it, then save.")
    }

    /** Suggests dishes from the library for the given empty slots. */
    suspend fun suggestWeek(
        library: List<RecipeSummary>,
        planned: List<PlanEntry>,
        notes: List<PlanNote>,
        slots: Map<LocalDate, List<Meal>>,
        wishes: String,
    ): WeekIdeas = withContext(Dispatchers.IO) {
        val lib = JSONArray()
        library.forEach { s ->
            lib.put(JSONArray().put(s.id).put(s.title).put(s.dishType).put(s.cuisine).put(s.lastPlanned ?: "never"))
        }
        val already = JSONArray()
        planned.forEach { already.put("${it.date} ${it.meal.key}: ${it.recipe.title}" + if (it.leftovers) " (leftovers)" else "") }
        notes.forEach { already.put("${it.date} ${it.meal.key}: ${it.text}") }
        val want = JSONObject()
        slots.forEach { (d, meals) -> want.put(d.toString(), JSONArray(meals.map { it.key })) }
        val r = Supa.ai(
            JSONObject().put("task", "week").put("library", lib).put("planned", already).put("slots", want).put("wishes", wishes.trim()),
        )
        val byId = library.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val plan = r.optJSONArray("plan") ?: JSONArray()
        val items = (0 until plan.length()).mapNotNull { i ->
            val o = plan.getJSONObject(i)
            val date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null
            val meal = Meal.from(o.optString("meal", "dinner"))
            val recipe = byId[o.optLong("recipeId")] ?: return@mapNotNull null
            if (slots[date]?.contains(meal) != true || !seen.add("$date$meal")) return@mapNotNull null
            Suggestion(date, meal, recipe, o.optString("reason"))
        }.sortedWith(compareBy({ it.date }, { it.meal.ordinal }))
        WeekIdeas(items, r.optString("summary"))
    }

    private fun toEditor(r: JSONObject, origin: String): EditorState {
        if (r.optString("title").equals("Not a recipe", true)) {
            throw IllegalStateException(r.optString("notes").ifBlank { "Those photos do not seem to show a recipe." })
        }
        val notes = listOf(r.optString("notes").trim(), origin).filter { it.isNotEmpty() }.joinToString("\n\n")
        r.put("notes", notes)
        r.put("status", RecipeStatus.TO_CHECK)
        r.remove("recipeId")
        return EditorState.fromJson(r.toString())
    }

    /** Shrinks a photo to at most 1568 px on the long side (what the model reads best), as JPEG. */
    private fun encode(ctx: Context, uri: Uri): String {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = 1568.0 / max(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** What to tell the person when a helper fails. */
    fun friendly(e: Throwable): String = when {
        e is IOException && e !is java.io.FileNotFoundException -> "No connection, or the answer took too long. Try again in a moment."
        e is ServerError && e.code == 404 -> "The AI helper is not switched on yet: its server function is missing."
        e is ServerError && e.code == 401 -> "Please sign in again (Settings, Household)."
        e is ServerError -> e.message ?: "The AI helper had a problem. Try again."
        e is IllegalStateException -> e.message ?: "That did not work. Try again."
        else -> "That did not work. Try again."
    }
}
