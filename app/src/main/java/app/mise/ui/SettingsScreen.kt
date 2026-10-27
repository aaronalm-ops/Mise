package app.mise.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mise.BuildConfig
import app.mise.R

@Composable
fun SettingsScreen(onBack: () -> Unit, onHousehold: () -> Unit) {
    val c = Mise.colours
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "",
            title = "Settings",
            leading = { IconTile(MiseIcons.Back, "Back", onBack) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SectionLabel("Sharing")
            Card {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onHousehold)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Household", style = Mise.type.bodyM, color = c.text)
                        Text(syncLine() ?: "Share recipes, plan and list with Janice", style = Mise.type.bodyS, color = c.text2)
                    }
                }
            }
            SectionLabel("Recipes")
            ImportRow()
            SectionLabel("Display")
            Card { InfoRow("Theme", "Follows your phone") }
            SectionLabel("About")
            Card {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) { MiseStamp(112.dp) }
                InfoRow("App", "Mise")
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.line))
                InfoRow("Version", BuildConfig.VERSION_NAME)
                MakerCredit()
            }
        }
    }
}

/**
 * Import recipes from a Mise recipe file (made from the Folio export). Opens the phone's file
 * picker, imports, and says how many came in. Titles already in Mise are skipped.
 */
@Composable
private fun ImportRow() {
    val c = Mise.colours
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = app.mise.data.RecipeRepo.get(context)
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        repo.scope.launch {
            val message = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val (imported, updated, skipped) = repo.importFile(text)
                buildString {
                    append(if (imported == 1) "1 recipe added." else "$imported recipes added.")
                    if (updated > 0) append(if (updated == 1) " 1 updated." else " $updated updated.")
                    if (skipped > 0) append(if (skipped == 1) " 1 was already in Mise and was skipped." else " $skipped were already in Mise and were skipped.")
                    if (imported > 0 || updated > 0) append(" They are marked To check in Recipes.")
                }
            }.getOrElse { "Could not import that file. " + (it.message ?: "") }
            busy = false
            result = message
        }
    }
    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(enabled = !busy, role = androidx.compose.ui.semantics.Role.Button) { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (busy) "Importing" else "Import recipes", style = Mise.type.bodyM, color = c.text)
                Text("From a Mise recipe file, e.g. your Folio export", style = Mise.type.bodyS, color = c.text2)
            }
        }
    }
    result?.let { msg ->
        ConfirmDialog(title = "Import", body = msg, confirm = "Done", dismiss = "Close", onConfirm = { result = null }, onDismiss = { result = null })
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = Mise.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Mise.type.body, color = c.text, modifier = Modifier.weight(1f))
        Text(value, style = Mise.type.bodyS, color = c.text2)
    }
}

/**
 * The approved About credit (Aaron Almeida Brand/SIGNATURE-LAYOUT.md), after the app and version:
 * the canonical two-colour Instinct VectorDrawable, 48 dp wide at the 720:544 ratio, untinted so
 * the cyan stays; 12 dp gap; "by Aaron Almeida" at 14/22 sp, prefix normal, name medium, kept
 * together on one line with non-breaking spaces. Charcoal body on light, off-white on dark.
 */
@Composable
fun MakerCredit() {
    val c = Mise.colours
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
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
                append("by ")
                withStyle(SpanStyle(fontWeight = FontWeight.W500)) { append("Aaron Almeida") }
            },
            style = Mise.type.credit,
            color = c.text,
        )
    }
}
