package app.mise.sync

import android.content.Context
import app.mise.data.EditorState
import app.mise.data.IngredientEntity
import app.mise.data.ListCheckEntity
import app.mise.data.ListExtraEntity
import app.mise.data.MiseDb
import app.mise.data.PlanEntryEntity
import app.mise.data.RecipeRepo
import app.mise.data.SyncRowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Working : SyncStatus
    data class Synced(val at: Long) : SyncStatus
    data object Offline : SyncStatus
    data class Problem(val message: String) : SyncStatus
}

/**
 * Keeps the household in step. While the app is open it sends local changes and fetches the
 * household's changes every few seconds, and straight away after any edit. Offline edits wait
 * (they stay marked in sync_rows) and go out when the connection is back. Last edit wins per
 * item; an item edited here and not yet sent is never overwritten by an older shared copy.
 */
object SyncEngine {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private var loop: Job? = null
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status

    private val db get() = MiseDb.get(app)

    fun init(context: Context) {
        app = context.applicationContext
        Supa.init(app)
    }

    fun poke() { pokes.trySend(Unit) }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runOnce()
                withTimeoutOrNull(5_000) { pokes.receive() }
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    fun active() = ::app.isInitialized && Supa.configured && Supa.signedIn && Supa.householdId != null

    suspend fun runOnce() {
        mutex.withLock {
            if (!active()) {
                _status.value = SyncStatus.Off
            } else {
                if (_status.value !is SyncStatus.Synced) _status.value = SyncStatus.Working
                val next: SyncStatus = try {
                    push()
                    pull()
                    SyncStatus.Synced(System.currentTimeMillis())
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (Supa.isOffline(e)) SyncStatus.Offline else SyncStatus.Problem(e.message ?: "Sync problem")
                }
                _status.value = next
            }
        }
    }

    /** Marks everything on this phone for sharing (after creating or joining a household). */
    suspend fun shareEverything() {
        val d = db.sync()
        d.recipeIds().forEach { MiseDb.mark(db, "recipe", it.toString()) }
        d.planIds().forEach { MiseDb.mark(db, "plan", it.toString()) }
        d.checkKeys().forEach { MiseDb.mark(db, "check", it) }
        d.extraIds().forEach { MiseDb.mark(db, "extra", it.toString()) }
        d.ingredientKeys().forEach { MiseDb.mark(db, "ingredient", it) }
        Supa.cursor = "1970-01-01T00:00:00+00:00"
    }

    // ---------- Sending ----------

    private val order = listOf("ingredient", "recipe", "plan", "extra", "check")

    private suspend fun push() {
        val household = Supa.householdId ?: return
        val d = db.sync()
        val dirty = d.dirty().sortedBy { order.indexOf(it.kind) }
        for (kindRows in dirty.groupBy { it.kind }.toSortedMap(compareBy { order.indexOf(it) }).values) {
            val payload = JSONArray()
            for (r in kindRows) payload.put(rowJson(household, r))
            Supa.upsertRows(payload)
            kindRows.forEach { d.clean(it.kind, it.localKey, it.dirty) }
        }
        val tombs = d.tombs()
        if (tombs.isNotEmpty()) {
            val payload = JSONArray()
            tombs.forEach { t ->
                payload.put(JSONObject().put("household_id", household).put("kind", t.kind).put("uid", t.uid).put("data", JSONObject()).put("deleted", true))
            }
            Supa.upsertRows(payload)
            tombs.forEach { d.clearTomb(it.kind, it.uid) }
        }
    }

    private suspend fun rowJson(household: String, r: SyncRowEntity): JSONObject {
        val dao = db.recipes()
        var deleted = false
        val data = JSONObject()
        when (r.kind) {
            "recipe" -> {
                val id = r.localKey.toLong()
                val entity = dao.recipe(id)
                val recipe = RecipeRepo.get(app).get(id)
                if (entity == null || recipe == null) deleted = true
                else data.put("state", JSONObject(EditorState.fromRecipe(recipe).toJson())).put("source", entity.source)
            }
            "plan" -> {
                val e = dao.planEntry(r.localKey.toLong())
                if (e == null) deleted = true else {
                    val recipeUid = db.sync().row("recipe", e.recipeId.toString())?.uid
                        ?: run { MiseDb.mark(db, "recipe", e.recipeId.toString()); db.sync().row("recipe", e.recipeId.toString())!!.uid }
                    data.put("date", e.date).put("meal", e.meal).put("recipe", recipeUid).put("servings", e.servings).put("choices", e.choices)
                }
            }
            "extra" -> {
                val e = dao.extra(r.localKey.toLong())
                if (e == null) deleted = true else data.put("week", e.weekStart).put("name", e.name).put("qty", e.qty).put("checked", e.checked)
            }
            "check" -> {
                val (week, key) = r.localKey.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                data.put("week", week).put("key", key).put("checked", dao.isChecked(week, key) > 0).put("by", Supa.displayName)
            }
            "ingredient" -> {
                val i = dao.ingredient(r.localKey)
                if (i == null) deleted = true else data.put("name", i.name).put("aisle", i.aisle).put("staple", i.isStaple)
            }
        }
        return JSONObject().put("household_id", household).put("kind", r.kind).put("uid", r.uid).put("data", data).put("deleted", deleted)
    }

    // ---------- Receiving ----------

    private suspend fun pull() {
        val household = Supa.householdId ?: return
        while (true) {
            val rows = Supa.changesSince(household, Supa.cursor)
            if (rows.length() == 0) return
            val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            for (row in list.sortedBy { order.indexOf(it.optString("kind")) }) {
                // One unreadable row must not block the rest; network errors still stop the round.
                try { applyRow(household, row) } catch (e: java.io.IOException) { throw e } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { android.util.Log.w("MiseSync", "Skipped a row", e) }
            }
            Supa.cursor = list.last().getString("updated_at")
            if (rows.length() < 500) return
        }
    }

    private suspend fun applyRow(household: String, row: JSONObject) {
        val kind = row.getString("kind")
        val uid = row.getString("uid")
        val data = row.optJSONObject("data") ?: JSONObject()
        val deleted = row.optBoolean("deleted")
        val d = db.sync()
        val dao = db.recipes()
        val mapped = d.byUid(kind, uid)
        if (mapped != null && mapped.dirty > 0) return // our newer edit goes out on the next push
        when (kind) {
            "recipe" -> {
                if (deleted) {
                    mapped?.let { dao.planIdsForRecipe(it.localKey.toLong()).forEach { p -> d.remove("plan", p.toString()) }; dao.deleteRecipe(it.localKey.toLong()); d.remove(kind, it.localKey) }
                    return
                }
                val state = EditorState.fromJson(data.getJSONObject("state").toString())
                val existingId = mapped?.localKey?.toLong()?.takeIf { dao.recipe(it) != null }
                val id = RecipeRepo.get(app).save(state.copy(recipeId = existingId), data.optString("source", "manual"), track = false)
                d.put(SyncRowEntity(kind, id.toString(), uid, 0))
            }
            "plan" -> {
                if (deleted) { mapped?.let { dao.deletePlan(it.localKey.toLong()); d.remove(kind, it.localKey) }; return }
                val recipeUid = data.optString("recipe")
                var recipeRow = d.byUid("recipe", recipeUid)
                if (recipeRow == null) {
                    Supa.row(household, "recipe", recipeUid)?.let { applyRow(household, it) }
                    recipeRow = d.byUid("recipe", recipeUid) ?: return
                }
                val entity = PlanEntryEntity(
                    id = mapped?.localKey?.toLong() ?: 0, date = data.getString("date"), meal = data.getString("meal"),
                    recipeId = recipeRow.localKey.toLong(), servings = data.optInt("servings", 4), choices = data.optString("choices"),
                    createdAt = System.currentTimeMillis(),
                )
                val id = if (mapped != null && dao.planEntry(entity.id) != null) { dao.updatePlan(entity); entity.id } else dao.insertPlan(entity.copy(id = 0))
                d.put(SyncRowEntity(kind, id.toString(), uid, 0))
            }
            "extra" -> {
                if (deleted) { mapped?.let { dao.deleteExtra(it.localKey.toLong()); d.remove(kind, it.localKey) }; return }
                val e = ListExtraEntity(
                    id = mapped?.localKey?.toLong() ?: 0, weekStart = data.getString("week"), name = data.optString("name"),
                    qty = data.optString("qty"), checked = data.optBoolean("checked"), createdAt = System.currentTimeMillis(),
                )
                val id = if (mapped != null && dao.extra(e.id) != null) { dao.updateExtra(e); e.id } else dao.insertExtra(e.copy(id = 0))
                d.put(SyncRowEntity(kind, id.toString(), uid, 0))
            }
            "check" -> {
                val week = data.optString("week"); val key = data.optString("key")
                if (!deleted && data.optBoolean("checked")) dao.check(ListCheckEntity(week, key, System.currentTimeMillis())) else dao.uncheck(week, key)
                d.put(SyncRowEntity(kind, "$week|$key", uid, 0))
            }
            "ingredient" -> {
                if (deleted) return
                val key = uid
                if (dao.ingredient(key) == null) {
                    dao.insertIngredient(IngredientEntity(name = data.optString("name", key), nameKey = key))
                }
                dao.setAisle(key, data.optString("aisle"))
                dao.setStaple(key, data.optBoolean("staple"))
                d.put(SyncRowEntity(kind, key, uid, 0))
            }
        }
    }
}
