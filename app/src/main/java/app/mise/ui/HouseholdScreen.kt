package app.mise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mise.sync.Supa
import app.mise.sync.SyncEngine
import app.mise.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-line sync state, used on Settings and the List. */
@Composable
fun syncLine(): String? {
    val s by SyncEngine.status.collectAsState()
    if (!SyncEngine.active()) return null
    return when (val v = s) {
        is SyncStatus.Synced -> "Shared · synced " + SimpleDateFormat("HH:mm", Locale.UK).format(Date(v.at))
        SyncStatus.Working, SyncStatus.Off -> "Shared · syncing"
        SyncStatus.Offline -> "Offline · will sync"
        is SyncStatus.Problem -> "Sync problem · " + v.message
    }
}

/**
 * Household sharing: sign in or create an account, then create a household or join one with
 * its six-character code. Once in, everything on this phone is shared and kept in step.
 */
@Composable
fun HouseholdScreen(onBack: () -> Unit) {
    val c = Mise.colours
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) } // re-read stored state after changes
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<String>>(emptyList()) }

    fun act(block: suspend () -> Unit) {
        busy = true; error = null; info = null
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) {
                error = if (Supa.isOffline(e)) "No connection. Try again when you are online." else (e.message ?: "Something went wrong")
            }
            busy = false; tick++
        }
    }

    LaunchedEffect(tick) {
        if (Supa.configured && Supa.signedIn && Supa.householdId != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = Supa.rpc("mise_my_household")
                    if (text.isBlank() || text == "null") null else JSONObject(text)
                }
            }.onSuccess { j ->
                if (j != null) {
                    Supa.setHousehold(j)
                    val arr = j.optJSONArray("members")
                    members = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(eyebrow = "Sharing", title = "Household", leading = { IconTile(MiseIcons.Back, "Back", onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            val refresh = tick // re-read stored state when this changes
            if (refresh < 0) return@Column
            when {
                !Supa.configured -> EmptyState("Sharing is not set up in this version", "This build has no server details. Install the latest release.")
                !Supa.signedIn -> SignInPanel(busy, onSignIn = { email, pw, name ->
                    act { Supa.setDisplayName(name); Supa.signIn(email, pw) }
                }, onSignUp = { email, pw, name ->
                    act {
                        Supa.setDisplayName(name)
                        val now = Supa.signUp(email, pw)
                        if (!now) info = "Account created. Open the confirmation email on this phone, tap the link, then come back and sign in. If the page after the link shows an error, that is fine: the account is confirmed."
                    }
                })
                Supa.householdId == null -> JoinPanel(busy, onCreate = { name ->
                    act {
                        val j = JSONObject(Supa.rpc("mise_create_household", JSONObject().put("p_name", name).put("p_display", Supa.displayName)))
                        Supa.setHousehold(j)
                        SyncEngine.shareEverything()
                        SyncEngine.poke()
                    }
                }, onJoin = { code ->
                    act {
                        val j = JSONObject(Supa.rpc("mise_join_household", JSONObject().put("p_code", code).put("p_display", Supa.displayName)))
                        Supa.setHousehold(j)
                        SyncEngine.shareEverything()
                        SyncEngine.poke()
                    }
                }, onSignOut = { Supa.signOut(); tick++ })
                else -> {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text((Supa.householdName ?: "Home"), style = Mise.type.displayS, color = c.text)
                            Text(syncLine() ?: "", style = Mise.type.bodyS, color = c.text2)
                        }
                    }
                    SectionLabel("Invite code")
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(Supa.inviteCode ?: "", style = Mise.type.number.copy(fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = 6.sp), color = c.text)
                            Text("On the other phone: Settings, Household, sign in, then Join with this code.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    SectionLabel("Members", members.size.toString())
                    Card {
                        if (members.isEmpty()) Text("Loading", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(16.dp))
                        members.forEachIndexed { i, m ->
                            if (i > 0) Divider()
                            Text(m.ifBlank { "Member" }, style = Mise.type.body, color = c.text, modifier = Modifier.padding(16.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Sync now", { SyncEngine.poke() }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Signed in as ${Supa.email ?: ""}", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Sign out", { Supa.signOut(); tick++ }, Modifier.fillMaxWidth())
                }
            }
            info?.let { Text(it, style = Mise.type.bodyS, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
        }
    }
}

@Composable
private fun SignInPanel(busy: Boolean, onSignIn: (String, String, String) -> Unit, onSignUp: (String, String, String) -> Unit) {
    val c = Mise.colours
    var name by remember { mutableStateOf(Supa.displayName) }
    var email by remember { mutableStateOf(Supa.email ?: "") }
    var pw by remember { mutableStateOf("") }
    Text("Share your recipes, plan and grocery list with your household. Everyone sees the same list, live.", style = Mise.type.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    MiseField(name, { name = it }, "Your first name", Modifier.fillMaxWidth(), capitalization = KeyboardCapitalization.Words)
    Spacer(Modifier.height(8.dp))
    MiseField(email, { email = it }, "Email", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None)
    Spacer(Modifier.height(8.dp))
    PasswordField(pw) { pw = it }
    Spacer(Modifier.height(16.dp))
    val ok = !busy && name.isNotBlank() && email.contains('@') && pw.length >= 6
    PrimaryButton(if (busy) "Please wait" else "Sign in", { onSignIn(email, pw, name) }, Modifier.fillMaxWidth(), enabled = ok)
    Spacer(Modifier.height(8.dp))
    GhostButton("Create an account", { if (ok) onSignUp(email, pw, name) }, Modifier.fillMaxWidth())
    Text("Password: at least 6 characters.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    val c = Mise.colours
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Mise.type.body.copy(color = c.text),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.text),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(c.surface, RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, c.line), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text("Password", style = Mise.type.body, color = c.text2)
                inner()
            }
        },
    )
}

@Composable
private fun JoinPanel(busy: Boolean, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onSignOut: () -> Unit) {
    val c = Mise.colours
    var name by remember { mutableStateOf("Home") }
    var code by remember { mutableStateOf("") }
    Text("Signed in as ${Supa.email ?: ""}", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    SectionLabel("Start a household")
    MiseField(name, { name = it }, "Household name", Modifier.fillMaxWidth(), capitalization = KeyboardCapitalization.Words)
    Spacer(Modifier.height(8.dp))
    PrimaryButton(if (busy) "Please wait" else "Create household", { onCreate(name) }, Modifier.fillMaxWidth(), enabled = !busy)
    Text("Everything on this phone (recipes, plan, list) is shared with the household.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    SectionLabel("Or join one")
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiseField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, "6-character code", Modifier.weight(1f), capitalization = KeyboardCapitalization.Characters)
    }
    Spacer(Modifier.height(8.dp))
    GhostButton("Join household", { if (code.length == 6 && !busy) onJoin(code) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(24.dp))
    GhostButton("Sign out", onSignOut, Modifier.fillMaxWidth())
}
