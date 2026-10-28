package app.mise.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mise.sync.Supa
import app.mise.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * After any sign-in: find this account's household and rejoin it, taking the household's copy
 * first so nothing is doubled. Returns the household's name, or null when there is none yet.
 */
suspend fun restoreHousehold(): String? = withContext(Dispatchers.IO) {
    val text = Supa.rpc("mise_my_household")
    if (text.isBlank() || text == "null") return@withContext null
    val j = JSONObject(text)
    Supa.setHousehold(j)
    SyncEngine.joinAndMerge()
    j.optString("name", "your household")
}

private enum class Step { Email, Code, Password, Name }

private fun friendly(e: Throwable): String =
    if (Supa.isOffline(e)) "No connection. Try again when you are online." else (e.message ?: "Something went wrong. Try again.")

/**
 * Sign in with email, no password: type your email, then the 6-digit code it receives. The
 * first time, the account is made by itself and Mise asks what to call you. Works inline in
 * any screen or sheet. [onDone] runs once signed in (and back in your household, if you have one).
 */
@Composable
fun AccountFlow(onDone: (household: String?) -> Unit, intro: String? = null) {
    val c = Mise.colours
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Step.Email) }
    var email by rememberSaveable { mutableStateOf(Supa.email ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Supa.displayName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf<String?>(null) }
    val codeFocus = remember { FocusRequester() }
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())

    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait-- } }
    LaunchedEffect(step) { if (step == Step.Code) { delay(250); runCatching { codeFocus.requestFocus() } } }

    fun finish() {
        scope.launch {
            busy = true
            val h = runCatching { restoreHousehold() }.getOrNull()
            restored = h
            busy = false
            if (Supa.displayName.isBlank()) step = Step.Name else onDone(h)
        }
    }

    fun attempt(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { error = friendly(e) }
            busy = false
        }
    }

    fun send() = attempt { Supa.sendCode(email); withContext(Dispatchers.Main) { step = Step.Code; code = ""; wait = 60 } }
    fun verify() = attempt { Supa.verifyCode(email, code); withContext(Dispatchers.Main) { finish() } }
    fun withPassword() = attempt { Supa.signIn(email, password); withContext(Dispatchers.Main) { finish() } }

    Column(Modifier.fillMaxWidth()) {
        when (step) {
            Step.Email -> {
                Text(intro ?: "Sign in to share your recipes, plan and list with your household, and to use Mise AI.", style = Mise.type.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                MiseField(
                    email, { email = it.trim() }, "Your email", Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
                    keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Send, onIme = { if (emailOk) send() },
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Sending" else "Email me a code", { send() }, Modifier.fillMaxWidth(), enabled = emailOk && !busy)
                Text("No password needed. New here? This makes your account.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextAction("Use my password instead", { error = null; step = Step.Password })
                }
            }
            Step.Code -> {
                Text("We sent a code to", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp))
                Text(email, style = Mise.type.bodyM, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                MiseField(
                    code,
                    { v ->
                        code = v.filter(Char::isDigit).take(8)
                        if (code.length == 6 && !busy) verify()
                    },
                    "6-digit code", Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
                    keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, onIme = { if (code.length >= 6) verify() },
                    focusRequester = codeFocus, big = true, textAlign = TextAlign.Center, minHeight = 60,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Checking" else "Sign in", { verify() }, Modifier.fillMaxWidth(), enabled = code.length >= 6 && !busy)
                Text("Check spam if it has not arrived in a minute.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (wait > 0) Text("Send again in ${wait}s", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
                    else TextAction("Send a new code", { send() }, Modifier.weight(1f))
                    TextAction("Change email", { error = null; step = Step.Email })
                }
            }
            Step.Password -> {
                MiseField(
                    email, { email = it.trim() }, "Your email", Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
                    keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None,
                )
                Spacer(Modifier.height(8.dp))
                MiseField(
                    password, { password = it }, "Password", Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None, password = true,
                    imeAction = ImeAction.Done, onIme = { if (emailOk && password.isNotEmpty()) withPassword() },
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Signing in" else "Sign in", { withPassword() }, Modifier.fillMaxWidth(), enabled = emailOk && password.isNotEmpty() && !busy)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextAction("Email me a code instead", { error = null; step = Step.Email })
                }
            }
            Step.Name -> {
                Text("One last thing", style = Mise.type.displayS, color = c.text, modifier = Modifier.padding(start = 4.dp))
                Text("What should Mise call you? It shows next to the things you tick.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp))
                MiseField(
                    name, { name = it.take(24) }, "Your first name", Modifier.fillMaxWidth().semantics { contentType = ContentType.PersonFirstName },
                    capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done,
                    onIme = { if (name.isNotBlank()) { Supa.setDisplayName(name); onDone(restored) } },
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Continue", { Supa.setDisplayName(name); onDone(restored) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
            }
        }
        error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
    }
}

/** The sign-in flow as a sheet, for places that need an account (AI helpers, sharing). */
@Composable
fun AccountSheet(onDismiss: () -> Unit, onDone: (String?) -> Unit) {
    MiseSheet(title = "Sign in", eyebrow = "Mise account", onDismiss = onDismiss) {
        AccountFlow(onDone = onDone)
    }
}

/** A short, friendly line for a household name ("Welcome back to Home"). */
fun welcomeLine(household: String?): String =
    if (household != null) "Signed in. You are back in $household." else "Signed in."
