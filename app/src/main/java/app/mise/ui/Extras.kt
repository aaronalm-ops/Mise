package app.mise.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mise.R
import app.mise.data.Aisle
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

/**
 * Add one-off items (milk, tissues) to the week's list. The name field is focused, the aisle is
 * guessed as you type (tap another to change it), recent items are one tap. Add keeps the sheet
 * open for the next item; Close when done. You stay on the tab you were on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddItemSheet(weekStart: LocalDate, onDone: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val recent by remember { repo.recentExtraNames() }.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Aisle?>(null) }
    var added by remember { mutableStateOf(listOf<String>()) }
    val focus = remember { FocusRequester() }
    val confirm = Haptic.rememberConfirm()
    val aisle = picked ?: if (name.isBlank()) null else Aisle.guess(name)

    LaunchedEffect(Unit) {
        delay(250)
        runCatching { focus.requestFocus() }
    }

    fun add(n: String = name, q: String = qty) {
        if (n.isBlank()) return
        val a = picked ?: Aisle.guess(n)
        repo.scope.launch { repo.addExtra(weekStart, n, q, a) }
        confirm()
        added = added + n.trim().replaceFirstChar { it.uppercase() }
        name = ""; qty = ""; picked = null
        runCatching { focus.requestFocus() }
    }

    MiseSheet(title = "Add to the list", eyebrow = weekTitle(weekStart), onDismiss = onDone) {
        MiseField(
            name, { name = it }, "Item, e.g. Milk", Modifier.fillMaxWidth(),
            focusRequester = focus, capitalization = KeyboardCapitalization.Sentences,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            MiseField(qty, { qty = it.take(24) }, "How much (optional)", Modifier.weight(1f), imeAction = ImeAction.Done, onIme = { add() })
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Add", { add() }, enabled = name.isNotBlank())
        }
        SectionLabel("Aisle", if (picked == null && aisle != null) "guessed" else null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Aisle.entries.forEach { a ->
                MiseChip(a.label, a == aisle) { picked = if (picked == a) null else a }
            }
        }
        val quick = recent.filter { r -> added.none { it.equals(r, true) } }.take(8)
        if (quick.isNotEmpty()) {
            SectionLabel("Recent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { r -> MiseChip(r, false) { add(r, "") } }
            }
        }
        if (added.isNotEmpty()) {
            Text(
                "Added: " + added.joinToString(", "),
                style = Mise.type.bodyS, color = c.text2,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp),
            )
        }
    }
}
