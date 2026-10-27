package app.mise.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mise.R
import app.mise.data.PlanRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The stoneware "mise" stamp, cropped to its clay circle. */
@Composable
fun MiseStamp(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.mise_stamp),
        contentDescription = "Mise",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

/** Cold start only: the stamp on the ground for a moment, then a 200 ms fade. Taps wait. */
@Composable
fun OpeningScreen(onDone: () -> Unit) {
    val c = Mise.colours
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(700)
        visible = false
        delay(220)
        onDone()
    }
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(200))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.base)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            contentAlignment = Alignment.Center,
        ) {
            MiseStamp(184.dp)
        }
    }
}

/** Add a one-off item (milk, tissues) to this week's list. */
@Composable
fun AddItemDialog(weekStart: LocalDate, onDone: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = c.surface,
        title = { Text("Add to the list", style = Mise.type.displayS, color = c.text) },
        text = {
            Column {
                MiseField(name, { name = it }, "Item, e.g. Milk", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MiseField(qty, { qty = it }, "How much (optional)", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val n = name; val q = qty
                    repo.scope.launch { repo.addExtra(weekStart, n, q) }
                }
                onDone()
            }) { Text("Add", style = Mise.type.bodyM, color = if (name.isBlank()) c.text2 else c.text) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", style = Mise.type.bodyM, color = c.text) } },
    )
}
