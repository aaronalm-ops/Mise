package app.mise.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/** One-line sync state for Settings. */
@Composable
fun syncLine(): String? {
    val s by SyncEngine.status.collectAsState()
    if (!SyncEngine.active()) return null
    val name = Supa.householdName ?: "Household"
    return when (val v = s) {
        is SyncStatus.Synced -> "$name · in step, " + SimpleDateFormat("HH:mm", Locale.UK).format(Date(v.minute * 60_000))
        SyncStatus.Working, SyncStatus.Off -> "$name · syncing"
        SyncStatus.Offline -> "$name · offline, will sync"
        is SyncStatus.Problem -> "$name · " + v.message
    }
}

/** "ABCDE FGHJK": the 10-character code in two easy halves. */
private fun spaced(code: String) = if (code.length == 10) code.substring(0, 5) + " " + code.substring(5) else code

private fun householdOf(text: String): JSONObject? = if (text.isBlank() || text == "null") null else JSONObject(text)

/**
 * Household sharing: sign in, then create a household or join one with its code. Signing in
 * again finds your household by itself. Joining first takes the household's copy and matches
 * recipes by name, so nothing is doubled.
 */
@Composable
fun HouseholdScreen(onBack: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<String>>(emptyList()) }
    var askLeave by remember { mutableStateOf(false) }

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
            runCatching { withContext(Dispatchers.IO) { householdOf(Supa.rpc("mise_my_household")) } }
                .onSuccess { j ->
                    if (j != null) {
                        Supa.setHousehold(j)
                        val arr = j.optJSONArray("members")
                        members = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
                    }
                }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(eyebrow = "Sharing", title = "Household", leading = { IconTile(MiseIcons.Back, "Back", onClick = onBack) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (tick < 0) return@Column // read stored state again whenever tick changes
            Supa.notice?.let { n ->
                Card {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, style = Mise.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
                        TextAction("OK", { Supa.notice = null; tick++ })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            when {
                !Supa.configured -> EmptyState("Sharing is not set up in this version", "This build has no server details. Install the latest release.")
                !Supa.signedIn -> SignInPanel(
                    busy,
                    onSignIn = { email, pw, name ->
                        act {
                            Supa.setDisplayName(name)
                            Supa.signIn(email, pw)
                            val j = householdOf(Supa.rpc("mise_my_household"))
                            if (j != null) {
                                Supa.setHousehold(j)
                                SyncEngine.joinAndMerge()
                                info = "Welcome back. You are in ${j.optString("name", "your household")} again."
                            }
                        }
                    },
                    onSignUp = { email, pw, name ->
                        act {
                            Supa.setDisplayName(name)
                            val now = Supa.signUp(email, pw)
                            if (!now) info = "Account created. Open the confirmation email on this phone and tap the link, then come back and sign in. If the page after the link shows an error, that is fine: the account is confirmed."
                        }
                    },
                )
                Supa.householdId == null -> JoinPanel(
                    busy,
                    onCreate = { name ->
                        act {
                            val j = JSONObject(Supa.rpc("mise_create_household", JSONObject().put("p_name", name).put("p_display", Supa.displayName)))
                            Supa.setHousehold(j)
                            SyncEngine.joinAndMerge()
                        }
                    },
                    onJoin = { code ->
                        act {
                            val j = JSONObject(Supa.rpc("mise_join_household", JSONObject().put("p_code", code).put("p_display", Supa.displayName)))
                            Supa.setHousehold(j)
                            SyncEngine.joinAndMerge()
                            info = "Joined. Recipes with the same name were matched, so nothing is doubled."
                        }
                    },
                    onSignOut = { act { SyncEngine.clearBookkeeping(); Supa.signOut() } },
                )
                else -> {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Supa.householdName ?: "Home", style = Mise.type.displayS, color = c.text, modifier = Modifier.weight(1f))
                                SyncPill()
                            }
                            Text(syncLine() ?: "", style = Mise.type.bodyS, color = c.text2)
                        }
                    }
                    if (members.size < 2) {
                        SectionLabel("Invite code")
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                val code = Supa.inviteCode ?: ""
                                Text(spaced(code), style = Mise.type.number.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 4.sp), color = c.text)
                                Text("On the other phone: Settings, Household, sign in, then Join with this code.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(top = 6.dp))
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GhostButton("Copy", { copy(ctx, code); Toasts.show("Code copied") })
                                    GhostButton("Share", { share(ctx, code) })
                                }
                            }
                        }
                    }
                    SectionLabel("Members", if (members.isEmpty()) null else "${members.size} of 2")
                    Card {
                        if (members.isEmpty()) Text("Loading", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(16.dp))
                        members.forEachIndexed { i, m ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.ifBlank { "Member" }, style = Mise.type.body, color = c.text, modifier = Modifier.weight(1f))
                                if (m == Supa.displayName) Pill("You")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Sync now", { SyncEngine.poke(); Toasts.show("Syncing") }, Modifier.fillMaxWidth())
                    Text("Signed in as ${Supa.email ?: ""}", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Sign out", { act { SyncEngine.clearBookkeeping(); Supa.signOut() } }, Modifier.weight(1f), enabled = !busy)
                        GhostButton("Leave household", { askLeave = true }, Modifier.weight(1f), danger = true, enabled = !busy)
                    }
                    Text("Either way, everything stays on this phone.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
            }
            info?.let { Text(it, style = Mise.type.bodyS, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            error?.let { Text(it, style = Mise.type.bodyS, color = c.danger, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
        }
    }

    if (askLeave) {
        ConfirmDialog(
            title = "Leave ${Supa.householdName ?: "the household"}?",
            body = "This phone stops sharing. Your recipes, plan and list stay here; the other phone keeps its copy too. You can join again with the code.",
            confirm = "Leave", dismiss = "Stay", danger = true,
            onConfirm = {
                askLeave = false
                act {
                    Supa.rpc("mise_leave_household")
                    Supa.setHousehold(null)
                    SyncEngine.clearBookkeeping()
                }
            },
            onDismiss = { askLeave = false },
        )
    }
}

private fun copy(ctx: Context, code: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Mise household code", code))
}

private fun share(ctx: Context, code: String) {
    val text = "Join our kitchen in Mise: Settings, Household, sign in, then Join with code ${spaced(code)}"
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Share the code"))
}

@Composable
private fun SignInPanel(busy: Boolean, onSignIn: (String, String, String) -> Unit, onSignUp: (String, String, String) -> Unit) {
    val c = Mise.colours
    var name by remember { mutableStateOf(Supa.displayName) }
    var email by remember { mutableStateOf(Supa.email ?: "") }
    var pw by remember { mutableStateOf("") }
    Text("Share your recipes, plan and grocery list with your household. You both see the same list, live.", style = Mise.type.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    MiseField(name, { name = it }, "Your first name", Modifier.fillMaxWidth(), capitalization = KeyboardCapitalization.Words)
    Spacer(Modifier.height(8.dp))
    MiseField(email, { email = it.trim() }, "Email", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None)
    Spacer(Modifier.height(8.dp))
    PasswordField(pw) { pw = it }
    Spacer(Modifier.height(16.dp))
    val ok = !busy && name.isNotBlank() && email.contains('@') && pw.length >= 6
    val why = when {
        busy -> "Please wait"
        name.isBlank() -> "Add your first name"
        !email.contains('@') -> "Add your email"
        pw.length < 6 -> "Password: 6 characters or more"
        else -> "Sign in"
    }
    PrimaryButton(why, { onSignIn(email, pw, name) }, Modifier.fillMaxWidth(), enabled = ok)
    Spacer(Modifier.height(8.dp))
    GhostButton("Create an account", { if (ok) onSignUp(email, pw, name) }, Modifier.fillMaxWidth(), enabled = ok)
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    val c = Mise.colours
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Mise.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
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
        TextAction(if (show) "Hide" else "Show", { show = !show })
    }
}

@Composable
private fun JoinPanel(busy: Boolean, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onSignOut: () -> Unit) {
    val c = Mise.colours
    var name by remember { mutableStateOf("Home") }
    var code by remember { mutableStateOf("") }
    Text("Signed in as ${Supa.email ?: ""}", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    SectionLabel("Join the other phone's household")
    MiseField(
        code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(10) }, "10-character code",
        Modifier.fillMaxWidth(), capitalization = KeyboardCapitalization.Characters,
    )
    Spacer(Modifier.height(8.dp))
    PrimaryButton(
        if (busy) "Please wait" else if (code.length < 10) "Enter the code to join" else "Join household",
        { onJoin(code) }, Modifier.fillMaxWidth(), enabled = !busy && code.length == 10,
    )
    SectionLabel("Or start one here")
    MiseField(name, { name = it }, "Household name", Modifier.fillMaxWidth(), capitalization = KeyboardCapitalization.Words)
    Spacer(Modifier.height(8.dp))
    GhostButton(if (busy) "Please wait" else "Create household", { onCreate(name) }, Modifier.fillMaxWidth(), enabled = !busy)
    Text("Start it on one phone only, then join from the other with the code.", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Spacer(Modifier.height(24.dp))
    GhostButton("Sign out", onSignOut, Modifier.fillMaxWidth(), enabled = !busy)
}
