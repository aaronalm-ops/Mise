package app.mise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The centre Add action (kept by Aaron, 26 Oct 2026). Panels v2 frame: task heading, reachable
 * Close, then the choices. In M0 the choices are shown but not live yet: New recipe arrives in
 * M1, List item in M2, Recipe from photo in Phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(onDismiss: () -> Unit) {
    val c = Mise.colours
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add", style = Mise.type.displayS, color = c.text, modifier = Modifier.weight(1f))
                IconTile(MiseIcons.Close, "Close", onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            AddChoice(MiseIcons.Library, "New recipe", "Next update")
            AddChoice(MiseIcons.List, "Item on the grocery list", "Coming with the list")
            AddChoice(MiseIcons.Camera, "Recipe from a photo", "Phase 2")
        }
    }
}

@Composable
private fun AddChoice(icon: ImageVector, title: String, note: String) {
    val c = Mise.colours
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(c.soft, shape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.text2, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Mise.type.bodyM, color = c.text2)
            Text(note.uppercase(), style = Mise.type.labelS, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
