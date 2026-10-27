package app.mise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(14.dp)

/** Screen header: mono eyebrow, Manrope title, optional 44 dp icon button on the right. */
@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Mise.colours
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        if (leading != null) {
            leading()
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (eyebrow.isNotEmpty()) Text(eyebrow.uppercase(), style = Mise.type.labelS, color = c.text2)
                Text(title, style = Mise.type.displayM, color = c.text, modifier = Modifier.padding(top = 4.dp))
            }
            if (actionIcon != null && onAction != null) IconTile(actionIcon, actionLabel ?: "", onAction)
        }
    }
}

/** The 44 dp bordered icon tile used in headers (settings, back, add item). */
@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Mise.colours
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(48.dp)
            .padding(2.dp)
            .background(c.surface, shape)
            .border(BorderStroke(1.dp, c.line), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = c.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    val c = Mise.colours
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp)) {
        Text(text.uppercase(), style = Mise.type.label, color = c.text2, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing.uppercase(), style = Mise.type.label, color = c.text2)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Mise.colours
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface, CardShape)
            .border(BorderStroke(1.dp, c.line), CardShape),
    ) { content() }
}

/** Empty state: what is here, and what happens next. No illustration, no spinner. */
@Composable
fun EmptyState(title: String, body: String) {
    val c = Mise.colours
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Mise.type.bodyM, color = c.text)
            Text(body, style = Mise.type.bodyS, color = c.text2)
        }
    }
}
