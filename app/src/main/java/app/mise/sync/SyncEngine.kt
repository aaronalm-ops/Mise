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
import app.mise.data.Shared
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
 * - Pulls overlap the previous one slightly, so a change that committed late is not missed; a
 *   row already applied (same server time) is skipped, so nothing is applied twice.
 * - Joining (or rejoining) is a merge that survives the app closing: it runs inside the sync
 *   loop until it finishes, takes the household's copy first and matches by name.
 * - One row the server refuses is set aside (logged), so it never blocks everything else.
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
    /** Recipes go before the dishes and ticks that refer to them. */
    private val order = listOf("ingredient", "ing_aisle", "ing_level", "ing_alias", "recipe", "plan", "note", "extra", "check", "setting")
    private const val OVERLAP_SECONDS = 30L
    private const val PAGE = 500
    private const val MEMBER_CHECK_MS = 10 * 60_000L

    /** Thrown inside a transaction to undo it: the row was edited here meanwhile. */
    private class Skip : Exception()

    fun init(context: Context) {
        app = context.applicationContext
        Supa.init(app)
        // Phones updated from an earlier version: their links belong to the current household.
        if (Supa.signedIn && Supa.householdId != null && Supa.linkedHousehold == null && !Supa.mergePending) {
            Supa.setLinkedHousehold(Supa.householdId)
        }
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
                checkMembership()
                if (Supa.householdId == null) return@withLock run { _status.value = SyncStatus.Off }
                if (Supa.mergePending) merge()
                // A failed send never stops the household's changes arriving.
                val pushError = runCatching { push() }.exceptionOrNull()
                if (pushError is kotlinx.coroutines.CancellationException) throw pushError
                if (pullToo) pull()
                if (pushError != null) throw pushError
                SyncStatus.Synced(System.currentTimeMillis() / 60_000)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                when {
                    Supa.isOffline(e) -> SyncStatus.Offline
                    Supa.isNotMember(e) -> { removedFromHousehold(); SyncStatus.Off }
                    !Supa.signedIn -> SyncStatus.Off
                    else -> SyncStatus.Problem(e.message ?: "Sync problem")
                }
            }
            val cur = _status.value
            if (cur != next) _status.value = next
        }
    }

    /** Every ten minutes: are we still in this household? Also picks up a new name or code. */
    private fun checkMembership() {
        val now = System.currentTimeMillis()
        if (now - Supa.memberCheckedAt < MEMBER_CHECK_MS) return
        val text = Supa.rpc("mise_my_household")
        Supa.memberCheckedAt = now
        if (text.isBlank() || text == "null") {
            removedFromHousehold()
            return
        }
        val j = JSONObject(text)
        if (j.optString("id") == Supa.householdId) Supa.refreshHousehold(j) else Supa.setHousehold(j)
    }

    private fun removedFromHousehold() {
        // Links are kept: rejoining the same household carries on without duplicates.
        Supa.setHousehold(null)
        Supa.notice = "You are no longer in that household. Your recipes, plan and list stay on this phone."
    }

    /** Sends anything waiting, then holds the loop's lock briefly so no round is mid-way (before signing out or leaving). */
    suspend fun stopAndWait() = withContext(Dispatchers.IO) {
        runCatching { runOnce(pullToo = false) }
        mutex.withLock { }
    }

    /** Forgets every link to shared rows. Local data stays. */
    suspend fun clearBookkeeping() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.sync().clearRows(); db.sync().clearTombs()
            Supa.setLinkedHousehold(null)
        }
    }

    /**
     * After creating, joining or rejoining a household. The merge itself runs in the sync loop
     * (see [merge]), so it finishes even if this screen closes or the app is killed midway.
     */
    suspend fun joinAndMerge() = withContext(Dispatchers.IO) {
        Supa.memberCheckedAt = System.currentTimeMillis()
        runOnce()
        poke()
    }

    /**
     * Takes the household's copy first (adopting this phone's items with the same name or the
     * same details, so nothing is doubled), then shares whatever this phone has that the
     * household does not. Safe to run again if it was interrupted.
     */
    private suspend fun merge() {
        val household = Supa.householdId ?: return
        val d = db.sync()
        if (Supa.linkedHousehold != household) {
            // A different household: the old links mean nothing there.
            d.clearRows(); d.clearTombs()
        } else {
            // Same household: keep what was shared; anything never sent is matched afresh.
            d.dropUnshared()
        }
        Supa.cursor = Supa.EPOCH
        pull(merge = true)
        suspend fun unlinked(kind: String, key: String) = d.row(kind, key) == null
        d.recipeIds().forEach { if (unlinked("recipe", "$it")) markInTx("recipe", "$it") }
        d.planIds().forEach { if (unlinked("plan", "$it")) markInTx("plan", "$it") }
        d.noteIds().forEach { if (unlinked("note", "$it")) markInTx("note", "$it") }
        d.checkKeys().forEach { if (unlinked("check", it)) markInTx("check", it) }
        d.extraIds().forEach { if (unlinked("extra", "$it")) markInTx("extra", "$it") }
        d.aisleKeys().forEach { if (unlinked("ing_aisle", it)) markInTx("ing_aisle", it) }
        d.levelKeys().forEach { if (unlinked("ing_level", it)) markInTx("ing_level", it) }
        d.aliasKeys().forEach { if (unlinked("ing_alias", it)) markInTx("ing_alias", it) }
        // Settings the household already has were applied by the pull; share only the missing ones.
        Shared.keys.forEach { if (unlinked("setting", it)) markInTx("setting", it) }
        Supa.setLinkedHousehold(household)
        Supa.mergePending = false
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
                try {
                    send(household, chunk)
                } catch (e: ServerError) {
                    if (!refusedRow(e)) throw e
                    // One bad row must not block the rest: send one by one, set aside the refused ones.
                    chunk.forEach { r ->
                        try {
                            send(household, listOf(r))
                        } catch (e2: ServerError) {
                            if (!refusedRow(e2)) throw e2
                            android.util.Log.w("MiseSync", "Server refused ${r.kind} ${r.localKey}: ${e2.message}")
                            d.park(r.kind, r.localKey)
                        }
                    }
                }
            }
        }
        val tombs = d.tombs()
        for (chunk in tombs.chunked(100)) {
            val payload = JSONArray()
            chunk.forEach { t ->
                payload.put(JSONObject().put("household_id", household).put("kind", t.kind).put("uid", t.uid).put("data", JSONObject()).put("deleted", true))
            }
            Supa.upsertRows(payload)
            chunk.forEach { d.clearTomb(it.kind, it.uid) }
        }
    }

    /** A 4xx about the data itself (too large, malformed), not about who we are. */
    private fun refusedRow(e: ServerError) = e.code in 400..499 && e.code != 401 && e.code != 429 && e.pgCode != "42501"

    private suspend fun send(household: String, rows: List<SyncRowEntity>) {
        val d = db.sync()
        val payload = JSONArray()
        rows.forEach { payload.put(rowJson(household, it)) }
        val stored = Supa.upsertRows(payload)
        for (i in 0 until stored.length()) {
            val s = stored.getJSONObject(i)
            d.setRemoteAt(s.getString("kind"), s.getString("uid"), s.getString("updated_at"))
        }
        // Only clears the mark if nothing changed while sending; a newer edit goes next round.
        rows.forEach { d.clean(it.kind, it.localKey, it.dirty) }
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
                    data.put("date", e.date).put("meal", e.meal).put("recipe", recipeUidFor(household, e.recipeId)).put("servings", e.servings)
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
                data.put("week", week).put("key", key).put("checked", c != null).put("amount", c?.amount ?: 0.0)
                    .put("unit", c?.unit ?: "").put("by", c?.checkedBy ?: "")
            }
            "ingredient", "ing_aisle", "ing_level", "ing_alias" -> {
                val i = dao.ingredient(r.localKey)
                if (i == null) deleted = true else {
                    data.put("name", i.name)
                    if (r.kind == "ingredient" || r.kind == "ing_aisle") data.put("aisle", i.aisle)
                    if (r.kind == "ingredient" || r.kind == "ing_level") data.put("stapleChoice", i.stapleChoice)
                    if (r.kind == "ingredient" || r.kind == "ing_alias") data.put("aliasOf", i.aliasOf)
                }
            }
            "setting" -> data.put("text", Shared.value(r.localKey))
        }
        return JSONObject().put("household_id", household).put("kind", r.kind).put("uid", r.uid).put("data", noNul(data)).put("deleted", deleted)
    }

    /** The shared id of a dish's recipe, sending the recipe first if the household has never had it. */
    private suspend fun recipeUidFor(household: String, recipeId: Long): String {
        val d = db.sync()
        var rr = d.row("recipe", recipeId.toString())
        if (rr != null && rr.remoteAt.isNotEmpty()) return rr.uid
        if (rr == null) {
            markInTx("recipe", recipeId.toString())
            rr = d.row("recipe", recipeId.toString())!!
        }
        val stored = Supa.upsertRows(JSONArray().put(rowJson(household, rr)))
        if (stored.length() > 0) d.setRemoteAt("recipe", rr.uid, stored.getJSONObject(0).getString("updated_at"))
        d.clean("recipe", rr.localKey, rr.dirty)
        return rr.uid
    }

    /** The server cannot store the NUL character in text; it is dropped (it is never typed). */
    private fun noNul(v: Any?): Any? = when (v) {
        is String -> if (v.indexOf('\u0000') >= 0) v.replace("\u0000", "") else v
        is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, noNul(v.get(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(noNul(v.get(i))) }
        else -> v
    }

    // ---------- Receiving ----------

    private suspend fun pull(merge: Boolean = false) {
        val household = Supa.householdId ?: return
        var since = overlapFrom(Supa.cursor)
        var inclusive = false
        while (true) {
            val rows = Supa.changesSince(household, since, PAGE, inclusive)
            if (rows.length() == 0) break
            val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            // Within a page, shared recipes go in before the dishes that use them.
            for (row in list.sortedBy { order.indexOf(it.optString("kind")).let { i -> if (i < 0) order.size else i } }) {
                applySafely(household, row, merge)
            }
            val last = list.last().getString("updated_at")
            if (last > Supa.cursor || Supa.cursor == Supa.EPOCH) Supa.cursor = last
            if (rows.length() < PAGE) break
            // Next page starts at the same time, so rows sharing it are not skipped; ones already
            // applied are recognised. If a whole page shares one time, step past it.
            inclusive = last != since
            since = last
        }
        retryPendingPlans(household, merge)
    }

    private suspend fun applySafely(household: String, row: JSONObject, merge: Boolean) {
        try {
            applyRow(household, row, merge)
        } catch (e: Exception) {
            if (e is java.io.IOException || e is ServerError || e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("MiseSync", "Skipped an unreadable row", e)
        }
    }

    /** Dishes whose recipe had not arrived yet: fetched again until they can be applied. */
    private suspend fun retryPendingPlans(household: String, merge: Boolean) {
        val pending = Supa.pendingPlans
        if (pending.isEmpty()) return
        val left = pending.toMutableSet()
        for (uid in pending) {
            val row = Supa.row(household, "plan", uid)
            if (row == null || row.optBoolean("deleted")) { left.remove(uid); continue }
            applySafely(household, row, merge)
            if (db.sync().byUid("plan", uid) != null) left.remove(uid)
        }
        Supa.pendingPlans = left
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

        // A dish needs its recipe here first; fetch it if this page did not bring it.
        if (kind == "plan" && !deleted) {
            val recipeUid = data.optString("recipe")
            if (d.byUid("recipe", recipeUid) == null) {
                Supa.row(household, "recipe", recipeUid)?.let { applyRow(household, it, merge) }
                if (d.byUid("recipe", recipeUid) == null) {
                    Supa.pendingPlans = Supa.pendingPlans + uid
                    return
                }
            }
        }
        // Recipes are saved through the repository, with the link recorded in the same transaction.
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
            try {
                RecipeRepo.get(app).save(state.copy(recipeId = existingId), data.optString("source", "manual"), track = false) { id ->
                    val now = d.byUid(kind, uid)
                    if (now != null && now.dirty > 0) throw Skip() // edited here meanwhile: keep that edit
                    if (now != null && now.localKey != id.toString()) d.remove(kind, now.localKey)
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
            } catch (_: Skip) {
            }
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
                    val date = data.optString("date", existing?.date ?: "").takeIf { it.isNotBlank() } ?: return@withTransaction
                    val entity = PlanEntryEntity(
                        id = existing?.id ?: 0, date = date, meal = data.optString("meal", existing?.meal ?: "dinner"),
                        recipeId = recipeRow.localKey.toLong(), servings = data.optInt("servings", existing?.servings ?: 4),
                        choices = data.optString("choices", existing?.choices ?: ""),
                        createdAt = data.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()),
                        leftovers = data.optBoolean("leftovers", existing?.leftovers ?: false),
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
                    val date = data.optString("date", existing?.date ?: "").takeIf { it.isNotBlank() } ?: return@withTransaction
                    val meal = data.optString("meal", existing?.meal ?: "dinner")
                    val text = data.optString("text", existing?.text ?: "")
                    val twin = if (existing == null && merge) dao.notesRange(date, date).firstOrNull { it.meal == meal && it.text.trim().equals(text.trim(), true) && d.row("note", it.id.toString()) == null } else null
                    val base = existing ?: twin
                    val n = PlanNoteEntity(id = base?.id ?: 0, date = date, meal = meal, text = text, createdAt = data.optLong("createdAt", base?.createdAt ?: System.currentTimeMillis()))
                    val id = if (base != null) { dao.updateNote(n); n.id } else dao.insertNote(n)
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
                "extra" -> {
                    if (deleted) { mapped?.let { dao.deleteExtra(it.localKey.toLong()); d.remove(kind, it.localKey) }; return@withTransaction }
                    val existing = mapped?.localKey?.toLong()?.let { dao.extra(it) }
                    val week = data.optString("week", existing?.weekStart ?: "").takeIf { it.isNotBlank() } ?: return@withTransaction
                    val name = data.optString("name", existing?.name ?: "")
                    val twin = if (existing == null && merge) dao.extrasForWeek(week).firstOrNull { it.name.trim().equals(name.trim(), true) && d.row("extra", it.id.toString()) == null } else null
                    val base = existing ?: twin
                    val e = ListExtraEntity(
                        id = base?.id ?: 0, weekStart = week, name = name, qty = data.optString("qty", base?.qty ?: ""),
                        checked = data.optBoolean("checked", base?.checked ?: false),
                        createdAt = data.optLong("createdAt", base?.createdAt ?: System.currentTimeMillis()),
                        aisle = data.optString("aisle", base?.aisle ?: ""), checkedBy = data.optString("by", base?.checkedBy ?: ""),
                    )
                    val id = if (base != null) { dao.updateExtra(e); e.id } else dao.insertExtra(e)
                    d.put(SyncRowEntity(kind, id.toString(), uid, 0, at))
                }
                "check" -> {
                    val week = data.optString("week"); val key = data.optString("key")
                    if (week.isBlank()) return@withTransaction
                    if (!deleted && data.optBoolean("checked")) {
                        dao.check(ListCheckEntity(week, key, System.currentTimeMillis(), data.optDouble("amount", 0.0), data.optString("by"), data.optString("unit")))
                    } else {
                        dao.uncheck(week, key)
                    }
                    d.put(SyncRowEntity(kind, "$week|$key", uid, 0, at))
                }
                "setting" -> {
                    if (deleted) return@withTransaction
                    if (data.has("text")) Shared.apply(uid, data.optString("text"))
                    d.put(SyncRowEntity(kind, uid, uid, 0, at))
                }
                "ingredient", "ing_aisle", "ing_level", "ing_alias" -> {
                    if (deleted) return@withTransaction
                    val key = uid
                    if (dao.ingredient(key) == null) dao.insertIngredient(IngredientEntity(name = data.optString("name", key).ifBlank { key }, nameKey = key))
                    val i = dao.ingredient(key) ?: return@withTransaction
                    if (data.has("aisle")) dao.setAisle(i.id, data.optString("aisle"))
                    if (data.has("stapleChoice")) dao.setStapleChoice(i.id, data.optInt("stapleChoice", -1))
                    else if (data.has("staple")) dao.setStapleChoice(i.id, if (data.optBoolean("staple")) 1 else -1)
                    if (data.has("aliasOf")) dao.setAlias(i.id, data.optString("aliasOf"))
                    d.put(SyncRowEntity(kind, key, uid, 0, at))
                }
            }
        }
    }
}
