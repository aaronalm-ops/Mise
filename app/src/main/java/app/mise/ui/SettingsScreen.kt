package app.mise.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
fun SettingsScreen(onBack: () -> Unit) {
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
            SectionLabel("Display")
            Card { InfoRow("Theme", "Follows your phone") }
            SectionLabel("About")
            Card {
                InfoRow("App", "Mise")
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.line))
                InfoRow("Version", BuildConfig.VERSION_NAME)
                MakerCredit()
            }
        }
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
