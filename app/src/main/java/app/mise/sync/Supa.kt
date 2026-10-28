package app.mise.sync

import android.content.Context
import android.content.SharedPreferences
import app.mise.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A reply from the server that is not a network failure (wrong password, bad code...). */
class ServerError(val code: Int, message: String, val pgCode: String? = null) : Exception(message)

/**
 * A small Supabase client over plain HTTPS: email sign-in, token refresh, database calls.
 * No SDK, so nothing extra ships in the app. The anon key is public by design; every row is
 * protected by row-level security on the server (supabase/mise-setup.sql).
 */
object Supa {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anon = BuildConfig.SUPABASE_ANON_KEY
    val configured: Boolean get() = base.isNotBlank() && anon.isNotBlank()

    private lateinit var prefs: SharedPreferences
    private val tokenLock = Any()
    /** Bumped on sign-out, so a token refresh already in flight cannot sign the phone back in. */
    @Volatile private var generation = 0

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("mise_sync", Context.MODE_PRIVATE)
    }

    // ---- Stored session and household ----

    val signedIn get() = prefs.getString("refresh", null) != null
    val email get() = prefs.getString("email", null)
    val userId get() = prefs.getString("user", null)
    val displayName get() = prefs.getString("display", "") ?: ""
    val householdId get() = prefs.getString("household", null)
    val householdName get() = prefs.getString("householdName", null)
    val inviteCode get() = prefs.getString("invite", null)
    var cursor: String
        get() = prefs.getString("cursor", EPOCH)!!
        set(v) { prefs.edit().putString("cursor", v).apply() }
    var notice: String?
        get() = prefs.getString("notice", null)
        set(v) { prefs.edit().apply { if (v == null) remove("notice") else putString("notice", v) }.apply() }

    const val EPOCH = "1970-01-01T00:00:00+00:00"

    private fun saveSession(j: JSONObject, gen: Int) {
        if (gen != generation) return
        val user = j.optJSONObject("user")
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.getString("refresh_token"))
            .putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
            .putString("email", user?.optString("email")?.takeIf { it.isNotBlank() } ?: email)
            .putString("user", user?.optString("id")?.takeIf { it.isNotBlank() } ?: userId)
            .apply()
    }

    fun setDisplayName(name: String) = prefs.edit().putString("display", name.trim()).apply()

    /** Sets (or clears) the household. A different household starts a fresh sync from zero. */
    fun setHousehold(j: JSONObject?): Boolean {
        val newId = j?.optString("id")
        val changed = newId != householdId
        val e = prefs.edit()
        if (j == null) {
            e.remove("household").remove("householdName").remove("invite").remove("cursor")
        } else {
            e.putString("household", newId).putString("householdName", j.optString("name")).putString("invite", j.optString("invite_code"))
            if (changed) e.putString("cursor", EPOCH)
        }
        e.apply()
        return changed
    }

    /** Ends the session on the server too, then forgets it here. */
    fun signOut() {
        val access = prefs.getString("access", null)
        generation++
        runCatching { if (access != null) call("POST", "/auth/v1/logout", "{}", access) }
        val display = displayName
        prefs.edit().clear().putString("display", display).apply()
    }

    // ---- HTTP ----

    private fun call(method: String, path: String, body: String?, token: String?, prefer: String? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("apikey", anon)
            c.setRequestProperty("Authorization", "Bearer " + (token ?: anon))
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw serverError(text, code)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun serverError(text: String, code: Int): ServerError = try {
        val j = JSONObject(text)
        val msg = listOf("msg", "error_description", "message", "error").firstNotNullOfOrNull { k -> j.optString(k).takeIf { it.isNotBlank() } } ?: "Error $code"
        ServerError(code, friendly(msg), j.optString("code").takeIf { it.isNotBlank() })
    } catch (e: Exception) {
        ServerError(code, "Error $code")
    }

    private fun friendly(msg: String): String = when {
        msg.contains("Invalid login credentials", true) -> "That email and password do not match."
        msg.contains("Email not confirmed", true) -> "Please confirm your email first: open the link we sent, then sign in."
        msg.contains("already registered", true) -> "That email already has an account. Sign in instead."
        msg.contains("Password should be", true) -> "Choose a password of at least 6 characters."
        else -> msg
    }

    // ---- Auth ----

    fun signIn(email: String, password: String) {
        val gen = generation
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        saveSession(JSONObject(call("POST", "/auth/v1/token?grant_type=password", body, null)), gen)
        prefs.edit().putString("email", email.trim()).apply()
    }

    /** Returns true when signed in straight away, false when the email must be confirmed first. */
    fun signUp(email: String, password: String): Boolean {
        val gen = generation
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val j = JSONObject(call("POST", "/auth/v1/signup", body, null))
        return if (j.has("access_token")) { saveSession(j, gen); true } else false
    }

    /** A valid access token, refreshed when close to expiry. One refresh at a time. */
    private fun token(): String = synchronized(tokenLock) {
        val access = prefs.getString("access", null)
        val expires = prefs.getLong("expires", 0)
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access
        val refresh = prefs.getString("refresh", null) ?: throw ServerError(401, "Not signed in")
        val gen = generation
        val j = JSONObject(call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), null))
        saveSession(j, gen)
        if (gen != generation) throw ServerError(401, "Signed out")
        j.getString("access_token")
    }

    private fun authed(method: String, path: String, body: String?, prefer: String? = null): String = try {
        call(method, path, body, token(), prefer)
    } catch (e: ServerError) {
        if (e.code != 401) throw e
        synchronized(tokenLock) { prefs.edit().remove("access").apply() }
        call(method, path, body, token(), prefer)
    }

    fun rpc(name: String, args: JSONObject = JSONObject()): String = authed("POST", "/rest/v1/rpc/$name", args.toString())

    // ---- Shared rows ----

    /** Upserts rows and returns them as stored (with the server's updated_at). */
    fun upsertRows(rows: JSONArray): JSONArray {
        if (rows.length() == 0) return JSONArray()
        val text = authed(
            "POST", "/rest/v1/mise_rows?on_conflict=household_id,kind,uid&select=kind,uid,updated_at",
            rows.toString(), "resolution=merge-duplicates,return=representation",
        )
        return if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    fun changesSince(household: String, since: String, limit: Int = 500): JSONArray {
        val q = "household_id=eq.$household&updated_at=gt." + java.net.URLEncoder.encode(since, "UTF-8") +
            "&order=updated_at.asc,kind.asc,uid.asc&limit=$limit&select=kind,uid,data,deleted,updated_at,updated_by"
        return JSONArray(authed("GET", "/rest/v1/mise_rows?$q", null))
    }

    fun row(household: String, kind: String, uid: String): JSONObject? {
        val q = "household_id=eq.$household&kind=eq.$kind&uid=eq." + java.net.URLEncoder.encode(uid, "UTF-8") + "&select=kind,uid,data,deleted,updated_at,updated_by"
        val a = JSONArray(authed("GET", "/rest/v1/mise_rows?$q", null))
        return if (a.length() > 0) a.getJSONObject(0) else null
    }

    fun isOffline(e: Throwable) = e is IOException

    /** Row-level security refused us: we are no longer in this household. */
    fun isNotMember(e: Throwable) = e is ServerError && (e.pgCode == "42501" || e.code == 403)
}
