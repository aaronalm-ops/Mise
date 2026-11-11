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

/**
 * The AI helpers. Everything goes through Mise's own server function (supabase/functions/mise-ai),
 * which holds the NVIDIA key; the phone only sends the photos or request and the signed-in token.
 * Each result is a draft you review: nothing is saved or planned without your tap.
 */
object Ai {
    val available: Boolean get() = Supa.configured && Supa.signedIn
    /** Signed in and not refused by the server (Mise AI is only on for the household's accounts). */
    val enabled: Boolean get() = available && Prefs.aiAllowed

    /** Every call goes through here, so a refusal hides the AI buttons and a success brings them back. */
    fun call(body: JSONObject): JSONObject = try {
        Supa.ai(body).also { if (!Prefs.aiAllowed) Prefs.storeAiAllowed(true) }
    } catch (e: ServerError) {
        if (e.code == 403) Prefs.storeAiAllowed(false)
        throw e
    }

    /** Reads up to four photos of one recipe (pages, a card, a screenshot) into a draft. */
    suspend fun recipeFromPhotos(ctx: Context, uris: List<Uri>, hint: String): EditorState = withContext(Dispatchers.IO) {
        val images = JSONArray()
        uris.take(4).forEach { images.put(JSONObject().put("media_type", "image/jpeg").put("data", encode(ctx, it))) }
        // The household profile is not sent here: a photo is copied faithfully, not adapted.
        val r = call(JSONObject().put("task", "photo").put("images", images).put("hint", hint.trim()))
        toEditor(r.getJSONObject("recipe"), "Read from a photo by Mise AI. Check the amounts, then save.")
    }

    /** Writes a recipe for a request ("butter chicken without cream, 30 minutes"). */
    suspend fun recipeFromIdea(prompt: String, serves: Int): EditorState = withContext(Dispatchers.IO) {
        val r = call(JSONObject().put("task", "idea").put("prompt", prompt.trim()).put("serves", serves).put("kitchen", Prefs.kitchen))
        toEditor(r.getJSONObject("recipe"), "Written by Mise AI for: “${prompt.trim()}”. Check it, then save.")
    }

    /** Full recipes for the library (the planner and Mise AI both need ingredients and swaps). */
    private suspend fun fullLibrary(ctx: Context, library: List<RecipeSummary>): List<Recipe> {
        val repo = RecipeRepo.get(ctx)
        return library.mapNotNull { repo.get(it.id) }
    }

    /**
     * Plans the empty slots on the phone from the household pattern: instant, offline, free, and
     * it never fails. [avoid] holds the last suggestion's dishes, so "Suggest again" differs.
     */
    suspend fun planWeek(
        ctx: Context,
        library: List<RecipeSummary>,
        planned: List<PlanEntry>,
        notes: List<PlanNote>,
        slots: Map<LocalDate, List<Meal>>,
        wishes: String,
        avoid: Set<Long> = emptySet(),
    ): WeekIdeas {
        val full = withContext(Dispatchers.IO) { fullLibrary(ctx, library) }
        return withContext(Dispatchers.Default) {
            Planner.suggest(
                Planner.Input(full, library, planned, notes, slots, Prefs.noCook, Prefs.defaultServings, wishes, avoid, System.nanoTime()),
            )
        }
    }

    /**
     * Mise AI's take on the week. The pattern plan goes along as a draft, so the model only has
     * to improve it (a short answer, so a quick one), and the screen keeps the draft if it fails.
     */
    suspend fun suggestWeek(
        ctx: Context,
        library: List<RecipeSummary>,
        planned: List<PlanEntry>,
        notes: List<PlanNote>,
        slots: Map<LocalDate, List<Meal>>,
        wishes: String,
        draft: WeekIdeas?,
    ): WeekIdeas = withContext(Dispatchers.IO) {
        val full = fullLibrary(ctx, library)
        val mains = full.filter { Planner.kindOf(it) != null }
        val sides = full.filter { Planner.isSide(it) }
        val lastById = library.associate { it.id to it.lastPlanned }
        val lib = JSONArray()
        mains.forEach { r ->
            lib.put(JSONArray().put(r.id).put(r.title).put(r.dishType).put(r.cuisine).put(lastById[r.id] ?: "never").put(Planner.proteins(r)))
        }
        val sideList = JSONArray()
        sides.forEach { sideList.put(JSONArray().put(it.id).put(it.title)) }
        val already = JSONArray()
        planned.forEach { already.put("${it.date} ${it.meal.key}: ${it.recipe.title}" + if (it.leftovers) " (leftovers)" else "") }
        notes.forEach { already.put("${it.date} ${it.meal.key}: ${it.text}") }
        val want = JSONObject()
        slots.forEach { (d, meals) -> want.put(d.toString(), JSONArray(meals.map { it.key })) }
        val draftJson = JSONArray()
        draft?.items?.forEach { d ->
            draftJson.put(
                JSONObject().put("date", d.date.toString()).put("meal", d.meal.key).put("recipeId", d.recipe.id).put("sideId", d.side?.id ?: 0)
                    .put("servings", d.servings ?: 0).put("covers", JSONArray(d.covers.map { (cd, cm) -> JSONObject().put("date", cd.toString()).put("meal", cm.key) })),
            )
        }
        val r = call(
            JSONObject().put("task", "week").put("library", lib).put("sides", sideList).put("planned", already).put("slots", want)
                .put("wishes", wishes.trim()).put("kitchen", Prefs.kitchen).put("servings", Prefs.defaultServings)
                .put("noCook", JSONArray(Prefs.noCook.sorted())).put("draft", draftJson),
        )
        val byId = library.associateBy { it.id }
        val mainIds = mains.map { it.id }.toSet()
        val sideIds = sides.map { it.id }.toSet()
        val alreadyIds = planned.filter { !it.leftovers }.map { it.recipe.id }.toMutableSet()
        val seen = mutableSetOf<String>()
        val plan = r.optJSONArray("plan") ?: JSONArray()
        val items = (0 until plan.length()).mapNotNull { i ->
            val o = plan.getJSONObject(i)
            val date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null
            val meal = Meal.from(o.optString("meal", "dinner"))
            val id = o.optLong("recipeId")
            // Checked here, whatever the model said: a real main dish, a free slot, no repeats.
            if (id !in mainIds || !alreadyIds.add(id)) return@mapNotNull null
            val recipe = byId[id] ?: return@mapNotNull null
            if (slots[date]?.contains(meal) != true || !seen.add("$date$meal")) return@mapNotNull null
            val side = o.optLong("sideId").takeIf { it in sideIds && it != id }?.let { byId[it] }
            val servings = o.optInt("servings", 0).takeIf { it in 1..24 }
            val covers = o.optJSONArray("covers")?.let { a ->
                (0 until a.length()).mapNotNull { k ->
                    val c = a.optJSONObject(k) ?: return@mapNotNull null
                    val d = runCatching { LocalDate.parse(c.getString("date")) }.getOrNull() ?: return@mapNotNull null
                    if (d.isAfter(date) && d.isBefore(date.plusDays(4))) d to Meal.from(c.optString("meal", "lunch")) else null
                }
            }.orEmpty().filter { (d, m) -> planned.none { it.date == d && it.meal == m } }
            Suggestion(date, meal, recipe, o.optString("reason"), side, servings, covers)
        }.sortedWith(compareBy({ it.date }, { it.meal.ordinal }))
        // Leftovers never fill a slot another suggestion cooks in, or one already covered.
        val covered = mutableSetOf<Pair<LocalDate, Meal>>()
        val kept = items.map { s ->
            val cv = s.covers.distinct().filter { it !in covered && noOtherCook(it, items, s) }
            covered += cv
            s.copy(covers = cv)
        }
        WeekIdeas(kept, r.optString("summary"), fromAi = true)
    }

    /** True when no other suggestion cooks in [slot] (so leftovers can fill it). */
    private fun noOtherCook(slot: Pair<LocalDate, Meal>, items: List<Suggestion>, self: Suggestion) =
        items.none { it !== self && it.date == slot.first && it.meal == slot.second }

    private fun toEditor(r: JSONObject, origin: String): EditorState {
        if (r.optString("title").equals("Not a recipe", true)) {
            throw IllegalStateException(r.optString("notes").ifBlank { "Those photos do not seem to show a recipe." })
        }
        val notes = listOf(r.optString("notes").trim(), origin).filter { it.isNotEmpty() }.joinToString("\n\n")
        r.put("notes", notes)
        // A unit the app does not know ("handful") is kept in the note, never saved as grams.
        fun fixUnits(a: JSONArray?) {
            if (a == null) return
            for (k in 0 until a.length()) {
                val ing = a.optJSONObject(k) ?: continue
                val u = ing.optString("unit").trim()
                if (u.isNotEmpty() && Measure.from(u) == Measure.NONE) {
                    ing.put("note", listOf(u, ing.optString("note")).filter { it.isNotBlank() }.joinToString(", "))
                    ing.put("unit", "")
                } else if (u.isNotEmpty()) {
                    ing.put("unit", Measure.from(u).key)
                }
            }
        }
        fixUnits(r.optJSONArray("ings"))
        r.optJSONArray("slots")?.let { sl -> for (k in 0 until sl.length()) sl.optJSONObject(k)?.optJSONArray("options")?.let { op -> for (m in 0 until op.length()) fixUnits(op.optJSONObject(m)?.optJSONArray("ings")) } }
        r.put("status", RecipeStatus.TO_CHECK)
        r.remove("recipeId")
        return EditorState.fromJson(r.toString())
    }

    /**
     * Shrinks a photo to about 1280 px on the long side and under 128 KB as JPEG, the size
     * NVIDIA's hosted models accept inline, stepping quality and size down until it fits.
     */
    private fun encode(ctx: Context, uri: Uri): String {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        var bmp = try { ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = 1280.0 / max(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        } } catch (e: IOException) {
            throw IllegalStateException("Mise could not open that photo. Try another one.")
        }
        var bytes: ByteArray
        var quality = 82
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            // NVIDIA accepts inline images up to 180,000 base64 characters: about 130 KB of JPEG.
            if (bytes.size <= 128_000 || (quality <= 46 && max(bmp.width, bmp.height) <= 960)) break
            if (quality > 55) {
                quality -= 12
            } else {
                val smaller = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8).toInt().coerceAtLeast(1), (bmp.height * 0.8).toInt().coerceAtLeast(1), true)
                bmp.recycle(); bmp = smaller
            }
        }
        bmp.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** What to tell the person when a helper fails. */
    fun friendly(e: Throwable): String = when {
        e is java.net.SocketTimeoutException -> "The AI took too long to answer. Try again; the next try often comes back faster."
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "No connection. Try again when you are online."
        e is IOException && e !is java.io.FileNotFoundException -> "No connection, or the answer took too long. Try again in a moment."
        e is ServerError && e.code == 404 -> "The AI helper is not switched on yet: its server function is missing."
        e is ServerError && e.code == 401 -> "Please sign in again (Settings, Household)."
        e is ServerError && e.code == 403 -> "Mise AI is not switched on for this account."
        e is ServerError && e.code >= 500 && (e.message.isNullOrBlank() || e.message!!.startsWith("Error ")) -> "Mise AI is having trouble right now. Try again in a few minutes."
        e is ServerError -> e.message ?: "The AI helper had a problem. Try again."
        e is IllegalStateException -> e.message ?: "That did not work. Try again."
        else -> "That did not work. Try again."
    }
}
