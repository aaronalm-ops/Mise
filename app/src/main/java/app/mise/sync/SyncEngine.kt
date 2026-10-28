package app.mise.sync

import android.content.Context
import androidx.room.withTransaction
import app.mise.data.EditorState
import app.mise.data.IngredientEntity
import app.mise.data.ListCheckEntity
import app.mise.data.ListExtraEntity
import app.mise.data.MiseDb
import app.mise.data.PlanEntryEntity
import app.mise.data.PlanNoteEntity
import app.mise.data.RecipeRepo
import app.mise.data.SyncRowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Working : SyncStatus
    /** [minute] is the sync time rounded to the minute, so screens redraw at most once a minute. */
    data class Synced(val minute: Long) : SyncStatus
    data object Offline : SyncStatus
    data class Problem(val message: String) : SyncStatus
}

/**
 * Keeps the household in step. While the app is open it sends local changes and fetches the
 * household's changes every few seconds, straight after any edit, and once more when the app
 * goes to the background. Offline edits wait (marked in sync_rows) and go out later.
 *
 * Rules that keep data safe:
 * - Last edit wins per item, but an item edited here and not yet sent is never overwritten.
 * - The check and the write happen in one transaction, so an edit made during a sync is kept.
 * - Pulls overlap the previous one by two minutes, so a change that committed late is not
 *   missed; a row already applied (same server time) is skipped, so nothing is applied twice.
 * - A server error stops the round without moving the cursor past rows not yet applied.
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
    private val order = listOf("ingredient", "recipe", "plan", "note", "extra", "check")
    private const val OVERLAP_SECONDS = 120L

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

    /** Stops the loop and sends anything still waiting, even as the app goes to the background. */
    fun stop() {
        loop?.cancel(); loop = null
        scope.launch { withContext(NonCancellable) { runOnce(pullToo = false) } }
    }

    fun active() = ::app.isInitialized && Supa.configured && Supa.signedIn && Supa.householdId != null

    suspend fun runOnce(pullToo: Boolean = true) {
        mutex.withLock {
            if (!active()) {
                _status.value = SyncStatus.Off
                return@withLock
            }
            if (_status.value !is SyncStatus.Synced) _status.value = SyncStatus.Working
            val next: SyncStatus = try {
                push()
                if (pullToo) pull()
                SyncStatus.Synced(System.currentTimeMillis() / 60_000)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                when {
                    Supa.isOffline(e) -> SyncStatus.Offline
                    Supa.isNotMember(e) -> { removedFromHousehold(); SyncStatus.Off }
                    else -> SyncStatus.Problem(e.message ?: "Sync problem")
                }
            }
            val cur = _status.value
            if (cur != next) _status.value = next
        }
    }

    private suspend fun removedFromHousehold() {
        Supa.setHousehold(null)
        Supa.notice = "You are no longer in that household. Your recipes, plan and list stay on this phone."
        clearBookkeeping()
    }

    /** Forgets every link to shared rows (after signing out or leaving). Local data stays. */
    suspend fun clearBookkeeping() = withContext(Dispatchers.IO) {
        db.sync().clearRows(); db.sync().clearTombs()
    }

    /**
     * After creating or joining a household: first take the household's copy (matching recipes
     * by name, so nothing is duplicated), then share whatever this phone has that the household
     * does not.
     */
    suspend fun joinAndMerge() = withContext(Dispatchers.IO) {
        mutex.withLock {
            Supa.cursor = Supa.EPOCH
            pull(merge = true)
            val d = db.sync()
            d.recipeIds().forEach { if (d.row("recipe", it.toString()) == null) markInTx("recipe", it.toString()) }
            d.planIds().forEach { if (d.row("plan", it.toString()) == null) markInTx("plan", it.toString()) }
            d.noteIds().forEach { if (d.row("note", it.toString()) == null) markInTx("note", it.toString()) }
            d.checkKeys().forEach { if (d.row("check", it) == null) markInTx("check", it) }
            d.extraIds().forEach { if (d.row("extra", it.toString()) == null) markInTx("extra", it.toString()) }
            d.ingredientKeys().forEach { if (d.row("ingredient", it) == null) markInTx("ingredient", it) }
        }
        poke()
    }

    private suspend fun markInTx(kind: String, key: String) = db.withTransaction { MiseDb.mark(db, kind, key) }

    // ---------- Sending ----------

    private suspend fun push() {
        val household = Supa.householdId ?: return
        val d = db.sync()
        val dirty = d.dirty()
        for (kind in order) {
            val rows = dirty.filter { it.kind == kind }
            if (rows.isEmpty()) continue
            for (chunk in rows.chunked(100)) {
                val payload = JSONArray()
                chunk.forEach { payload.put(rowJson(household, it)) }
                val stored = Supa.upsertRows(payload)
                for (i in 0 until stored.length()) {
                    val s = stored.getJSONObject(i)
                    d.setRemoteAt(s.getString("kind"), s.getString("uid"), s.getString("updated_at"))
                }
                chunk.forEach { d.clean(it.kind, it.localKey, it.dirty) }
            }
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
                    val recipeRow = db.sync().row("recipe", e.recipeId.toString())
                    val recipeUid = recipeRow?.uid ?: run {
                        // The recipe was never shared: share it in this same round, first.
                        markInTx("recipe", e.recipeId.toString())
                        val rr = db.sync().row("recipe", e.recipeId.toString())!!
                        val stored = Supa.upsertRows(JSONArray().put(rowJson(household, rr)))
                        if (stored.length() > 0) db.sync().setRemoteAt("recipe", rr.uid, stored.getJSONObject(0).getString("updated_at"))
                        db.sync().clean("recipe", rr.localKey, rr.dirty)
                        rr.uid
                    }
                    data.put("date", e.date).put("meal", e.meal).put("recipe", recipeUid).put("servings", e.servings)
                        .put("choices", e.choices).put("leftovers", e.leftovers).put("createdAt", e.createdAt)
                }
            }
            "note" -> {
                val n = dao.note(r.localKey.toLong())
                if (n == null) deleted = true else data.put("date", n.date).put("meal", n.meal).put("text", n.text).put("createdAt", n.createdAt)
            }
            "extra" -> {
                val e = dao.extra(r.localKey.toLong())
                if (e == null) deleted = true else data.put("week", e.weekStart).put("name", e.name).put("qty", e.qty)
                    .put("checked", e.checked).put("aisle", e.aisle).put("by", e.checkedBy).put("createdAt", e.createdAt)
            }
            "check" -> {
                val (week, key) = r.localKey.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val c = dao.checkRow(week, key)
                data.put("week", week).put("key", key).put("checked", c != null).put("amount", c?.amount ?: 0.0).put("by", c?.checkedBy ?: "")
            }
            "ingredient" -> {
                val i = dao.ingredient(r.localKey)
                if (i == null) deleted = true else data.put("name", i.name).put("aisle", i.aisle).put("stapleChoice", i.stapleChoice).put("aliasOf", i.aliasOf)
            }
        }
        return JSONObject().put("household_id", household).put("kind", r.kind).put("uid", r.uid).put("data", data).put("deleted", deleted)
    }

    // ---------- Receiving ----------

    private suspend fun pull(merge: Boolean = false) {
        val household = Supa.householdId ?: return
        var since = overlapFrom(Supa.cursor)
        while (true) {
            val rows = Supa.changesSince(household, since)
            if (rows.length() == 0) return
            val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            // Within a page, shared recipes go in before the dishes that use them.
            for (row in list.sortedBy { order.indexOf(it.optString("kind")) }) {
                try {
                    applyRow(household, row, merge)
                } catch (e: Exception) {
                    if (e is java.io.IOException || e is ServerError || e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("MiseSync", "Skipped an unreadable row", e)
                }
            }
            val last = list.last().getString("updated_at")
            if (last > Supa.cursor || Supa.cursor == Supa.EPOCH) Supa.cursor = last
            if (rows.length() < 500) return
            since = last
        }
    }

    private fun overlapFrom(cursor: String): String = if (cursor == Supa.EPOCH) cursor else try {
        OffsetDateTime.parse(cursor).minusSeconds(OVERLAP_SECONDS).toString()
    } catch (e: Exception) {
        Supa.EPOCH
    }

    private suspend fun applyRow(household: String, row: JSONObject, merge: Boolean) {
        val kind = row.getString("kind")
        val uid = row.getString("uid")
        val at = row.optString("updated_at")
        val data = row.optJSONObject("data") ?: JSONObject()
        val deleted = row.optBoolean("deleted")
        val d = db.sync()
        val dao = db.recipes()

        // A plan entry needs its recipe here first; fetch it if this page did not bring it.
        if (kind == "plan" && !deleted) {
            val recipeUid = data.optString("recipe")
            if (d.byUid("recipe", recipeUid) == null) {
                Supa.row(household, "recipe", recipeUid)?.let { applyRow(household, it, merge) }
                if (d.byUid("recipe", recipeUid) == null) return
            }
        }
        // Recipes are saved through the repository (its own transaction), outside the one below.
        if (kind == "recipe" && !deleted) {
            val mapped = d.byUid(kind, uid)
            if (mapped != null && (mapped.dirty > 0 || mapped.remoteAt == at)) return
            val state = EditorState.fromJson(data.getJSONObject("state").toString())
            var existingId = mapped?.localKey?.toLong()?.takeIf { dao.recipe(it) != null }
            if (existingId == null && merge) {
                // Joining: adopt a local recipe with the same name instead of adding a second copy.
                val title = state.title.trim().lowercase()
                existingId = dao.titleRows().firstOrNull { it.title.trim().lowercase() == title && d.row("recipe", it.id.toString()) == null }?.id
            }
            val id = RecipeRepo.get(app).save(state.copy(recipeId = existingId), data.optString("source", "manual"), track = false)
            d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
            return
        }

        db.withTransaction {
            val mapped = d.byUid(kind, uid)
            if (mapped != null && (mapped.dirty > 0 || mapped.remoteAt == at)) return@withTransaction
            when (kind) {
                "recipe" -> mapped?.let {
                    val rid = it.localKey.toLong()
                    dao.planIdsForRecipe(rid).forEach { p -> d.remove("plan", p.toString()) }
                    dao.deleteRecipe(rid); d.remove(kind, it.localKey)
                }
                "plan" -> {
                    if (deleted) { mapped?.let { dao.deletePlan(it.localKey.toLong()); d.remove(kind, it.localKey) }; return@withTransaction }
                    val recipeRow = d.byUid("recipe", data.optString("recipe")) ?: return@withTransaction
                    val existing = mapped?.localKey?.toLong()?.let { dao.planEntry(it) }
                    val entity = PlanEntryEntity(
                        id = existing?.id ?: 0, date = data.getString("date"), meal = data.getString("meal"),
                        recipeId = recipeRow.localKey.toLong(), servings = data.optInt("servings", 4), choices = data.optString("choices"),
                        createdAt = data.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()), leftovers = data.optBoolean("leftovers"),
                    )
                    var id = existing?.id
                    if (id != null) dao.updatePlan(entity) else {
                        // Joining: the same dish already on this phone becomes the shared one.
                        val twin = if (merge) dao.planRange(entity.date, entity.date).firstOrNull { it.meal == entity.meal && it.recipeId == entity.recipeId && d.row("plan", it.id.toString()) == null } else null
                        id = twin?.id ?: dao.insertPlan(entity)
                        if (twin != null) dao.updatePlan(entity.copy(id = twin.id))
                    }
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
                "note" -> {
                    if (deleted) { mapped?.let { dao.deleteNote(it.localKey.toLong()); d.remove(kind, it.localKey) }; return@withTransaction }
                    val existing = mapped?.localKey?.toLong()?.let { dao.note(it) }
                    val n = PlanNoteEntity(id = existing?.id ?: 0, date = data.getString("date"), meal = data.getString("meal"), text = data.optString("text"), createdAt = data.optLong("createdAt", System.currentTimeMillis()))
                    val id = if (existing != null) { dao.updateNote(n); n.id } else dao.insertNote(n)
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
                "extra" -> {
                    if (deleted) { mapped?.let { dao.deleteExtra(it.localKey.toLong()); d.remove(kind, it.localKey) }; return@withTransaction }
                    val existing = mapped?.localKey?.toLong()?.let { dao.extra(it) }
                    val e = ListExtraEntity(
                        id = existing?.id ?: 0, weekStart = data.getString("week"), name = data.optString("name"), qty = data.optString("qty"),
                        checked = data.optBoolean("checked"), createdAt = data.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()),
                        aisle = data.optString("aisle"), checkedBy = data.optString("by"),
                    )
                    val id = if (existing != null) { dao.updateExtra(e); e.id } else dao.insertExtra(e)
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
                "check" -> {
                    val week = data.optString("week"); val key = data.optString("key")
                    if (!deleted && data.optBoolean("checked")) dao.check(ListCheckEntity(week, key, System.currentTimeMillis(), data.optDouble("amount", 0.0), data.optString("by")))
                    else dao.uncheck(week, key)
                    d.put(SyncRowEntity(kind, "$week|$key", uid, 0, at))
                }
                "ingredient" -> {
                    if (deleted) return@withTransaction
                    val key = uid
                    if (dao.ingredient(key) == null) dao.insertIngredient(IngredientEntity(name = data.optString("name", key), nameKey = key))
                    val i = dao.ingredient(key) ?: return@withTransaction
                    dao.setAisle(i.id, data.optString("aisle"))
                    if (data.has("stapleChoice")) dao.setStapleChoice(i.id, data.optInt("stapleChoice", -1))
                    else if (data.has("staple")) dao.setStapleChoice(i.id, if (data.optBoolean("staple")) 1 else -1)
                    dao.setAlias(i.id, data.optString("aliasOf"))
                    d.put(SyncRowEntity(kind, key, uid, 0, at))
                }
            }
        }
    }
}
