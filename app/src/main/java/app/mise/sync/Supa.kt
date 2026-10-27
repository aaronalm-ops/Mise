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
class ServerError(val code: Int, message: String) : Exception(message)

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

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("mise_sync", Context.MODE_PRIVATE)
    }

    // ---- Stored session and household ----

    val signedIn get() = prefs.getString("refresh", null) != null
    val email get() = prefs.getString("email", null)
    val displayName get() = prefs.getString("display", "") ?: ""
    val householdId get() = prefs.getString("household", null)
    val householdName get() = prefs.getString("householdName", null)
    val inviteCode get() = prefs.getString("invite", null)
    var cursor: String
        get() = prefs.getString("cursor", "1970-01-01T00:00:00+00:00")!!
        set(v) { prefs.edit().putString("cursor", v).apply() }

    private fun saveSession(j: JSONObject) {
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.getString("refresh_token"))
            .putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
            .putString("email", j.optJSONObject("user")?.optString("email") ?: email)
            .apply()
    }

    fun setDisplayName(name: String) = prefs.edit().putString("display", name.trim()).apply()

    fun setHousehold(j: JSONObject?) {
        val e = prefs.edit()
        if (j == null) {
            e.remove("household").remove("householdName").remove("invite").remove("cursor")
        } else {
            e.putString("household", j.getString("id")).putString("householdName", j.optString("name")).putString("invite", j.optString("invite_code"))
        }
        e.apply()
    }

    fun signOut() {
        prefs.edit().clear().apply()
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
            if (code !in 200..299) throw ServerError(code, readable(text, code))
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun readable(text: String, code: Int): String = try {
        val j = JSONObject(text)
        listOf("msg", "error_description", "message", "error").firstNotNullOfOrNull { k -> j.optString(k).takeIf { it.isNotBlank() } } ?: "Error $code"
    } catch (e: Exception) {
        "Error $code"
    }

    // ---- Auth ----

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        saveSession(JSONObject(call("POST", "/auth/v1/token?grant_type=password", body, null)))
        prefs.edit().putString("email", email.trim()).apply()
    }

    /** Returns true when signed in straight away, false when the email must be confirmed first. */
    fun signUp(email: String, password: String): Boolean {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val j = JSONObject(call("POST", "/auth/v1/signup", body, null))
        return if (j.has("access_token")) { saveSession(j); true } else false
    }

    /** A valid access token, refreshed when close to expiry. */
    private fun token(): String {
        val access = prefs.getString("access", null)
        val expires = prefs.getLong("expires", 0)
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access
        val refresh = prefs.getString("refresh", null) ?: throw ServerError(401, "Not signed in")
        val j = JSONObject(call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh).toString(), null))
        saveSession(j)
        return j.getString("access_token")
    }

    private fun authed(method: String, path: String, body: String?, prefer: String? = null): String = try {
        call(method, path, body, token(), prefer)
    } catch (e: ServerError) {
        if (e.code != 401) throw e
        prefs.edit().remove("access").apply()
        call(method, path, body, token(), prefer)
    }

    fun rpc(name: String, args: JSONObject = JSONObject()): String = authed("POST", "/rest/v1/rpc/$name", args.toString())

    // ---- Shared rows ----

    fun upsertRows(rows: JSONArray) {
        if (rows.length() == 0) return
        authed("POST", "/rest/v1/mise_rows?on_conflict=household_id,kind,uid", rows.toString(), "resolution=merge-duplicates,return=minimal")
    }

    fun changesSince(household: String, since: String, limit: Int = 500): JSONArray {
        val q = "household_id=eq.$household&updated_at=gt." + java.net.URLEncoder.encode(since, "UTF-8") +
            "&order=updated_at.asc&limit=$limit&select=kind,uid,data,deleted,updated_at"
        return JSONArray(authed("GET", "/rest/v1/mise_rows?$q", null))
    }

    fun row(household: String, kind: String, uid: String): JSONObject? {
        val q = "household_id=eq.$household&kind=eq.$kind&uid=eq." + java.net.URLEncoder.encode(uid, "UTF-8") + "&select=kind,uid,data,deleted,updated_at"
        val a = JSONArray(authed("GET", "/rest/v1/mise_rows?$q", null))
        return if (a.length() > 0) a.getJSONObject(0) else null
    }

    fun isOffline(e: Throwable) = e is IOException
}
