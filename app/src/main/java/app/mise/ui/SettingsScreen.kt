package app.mise.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.mise.BuildConfig
import app.mise.R
import app.mise.Reminder
import app.mise.data.Prefs
import app.mise.data.RecipeRepo
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onHousehold: () -> Unit) {
    val c = Mise.colours
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(eyebrow = "", title = "Settings", leading = { IconTile(MiseIcons.Back, "Back", onClick = onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SectionLabel("Sharing")
            Card {
                NavRow("Household", syncLine() ?: "Sign in to share recipes, plan and list with your household", onHousehold)
            }

            SectionLabel("Planning")
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Servings for a dish", style = Mise.type.body, color = c.text)
                        Text(if (Prefs.defaultServings == 0) "Each recipe's own" else "New dishes start at this", style = Mise.type.bodyS, color = c.text2)
                    }
                    Stepper(
                        Prefs.defaultServings, { Prefs.chooseDefaultServings(it) }, min = 0, max = 12, label = "servings",
                        display = if (Prefs.defaultServings == 0) "–" else Prefs.defaultServings.toString(),
                    )
                }
                Divider()
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Week starts on", style = Mise.type.body, color = c.text, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).forEach { d ->
                            MiseChip(d.getDisplayName(TextStyle.FULL, Locale.UK), Prefs.weekStart == d) { Prefs.chooseWeekStart(d) }
                        }
                    }
                }
            }

            SectionLabel("Planning reminder")
            ReminderCard()

            SectionLabel("Recipes")
            ImportCard()

            SectionLabel("Display")
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiseChip("Phone", Prefs.theme == Prefs.Theme.SYSTEM) { Prefs.chooseTheme(Prefs.Theme.SYSTEM) }
                    MiseChip("Light", Prefs.theme == Prefs.Theme.LIGHT) { Prefs.chooseTheme(Prefs.Theme.LIGHT) }
                    MiseChip("Dark", Prefs.theme == Prefs.Theme.DARK) { Prefs.chooseTheme(Prefs.Theme.DARK) }
                }
            }

            SectionLabel("About")
            Card {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) { MiseStamp(112.dp) }
                InfoRow("App", "Mise")
                Divider(Modifier.padding(horizontal = 16.dp))
                InfoRow("Version", BuildConfig.VERSION_NAME)
                MakerCredit()
            }
        }
    }
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Mise.type.bodyM, color = c.text)
            Text(sub, style = Mise.type.bodyS, color = c.text2)
        }
        Icon(MiseIcons.Forward, contentDescription = null, tint = c.text2, modifier = Modifier.size(18.dp))
    }
}

/** A nudge to plan next week, on the day and hour you choose. Tapping it opens next week. */
@Composable
private fun ReminderCard() {
    val c = Mise.colours
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Prefs.setReminder(true); Reminder.schedule(ctx)
            Toasts.show("Reminder on: " + reminderText())
        } else {
            Toasts.show("Notifications are off for Mise. Turn them on in the phone's settings to get the reminder.")
        }
    }
    fun turnOn() {
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Prefs.setReminder(true); Reminder.schedule(ctx)
            Toasts.show("Reminder on: " + reminderText())
        }
    }
    Card {
        MiseSwitch(
            Prefs.reminderOn,
            { on -> if (on) turnOn() else { Prefs.setReminder(false); Reminder.schedule(ctx) } },
            "Remind me to plan the week",
            sub = if (Prefs.reminderOn) reminderText() else "Off",
        )
        if (Prefs.reminderOn) {
            Divider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.values().forEach { d ->
                    MiseChip(d.getDisplayName(TextStyle.SHORT, Locale.UK), Prefs.reminderDay == d) {
                        Prefs.setReminder(true, day = d); Reminder.schedule(ctx)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", style = Mise.type.body, color = c.text, modifier = Modifier.weight(1f))
                Stepper(
                    Prefs.reminderHour, { Prefs.setReminder(true, hour = it); Reminder.schedule(ctx) }, min = 6, max = 22, label = "hour",
                    display = "%02d:00".format(Prefs.reminderHour),
                )
            }
        }
    }
}

private fun reminderText(): String =
    Prefs.reminderDay.getDisplayName(TextStyle.FULL, Locale.UK) + "s at " + "%02d:00".format(Prefs.reminderHour)

/**
 * Import recipes from a Mise recipe file (made from the Folio export). The result is kept by the
 * app, so it is still here if you leave Settings while it runs.
 */
@Composable
private fun ImportCard() {
    val c = Mise.colours
    val context = LocalContext.current
    val repo = RecipeRepo.get(context)
    val busy by repo.importing.collectAsState()
    val result by repo.importResult.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        if (text == null) Toasts.show("Could not open that file.") else repo.importFile(text)
    }
    Card {
        NavRow(
            if (busy) "Importing…" else "Import recipes",
            "From a Mise recipe file, e.g. your Folio export",
        ) { if (!busy) picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        result?.let { msg ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = Mise.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
                TextAction("Done", { repo.clearImportResult() })
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = Mise.colours
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Mise.type.body, color = c.text, modifier = Modifier.weight(1f))
        Text(value, style = Mise.type.bodyS, color = c.text2)
    }
}

/**
 * The approved About credit (Aaron Almeida Brand/SIGNATURE-LAYOUT.md), after the app and version:
 * the canonical two-colour Instinct VectorDrawable, 48 dp wide at the 720:544 ratio, untinted so
 * the cyan stays; 12 dp gap; "by Aaron Almeida" at 14/22 sp, prefix normal, name medium, kept
 * together on one line. Charcoal body on light, off-white on dark.
 */
@Composable
fun MakerCredit() {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(if (c.isDark) R.drawable.aaron_instinct_reverse else R.drawable.aaron_instinct),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 48.dp, height = 36.27.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                append("by ")
                withStyle(SpanStyle(fontWeight = FontWeight.W500)) { append("Aaron Almeida") }
            },
            style = Mise.type.credit,
            color = c.text,
        )
    }
}
