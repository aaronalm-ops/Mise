package app.mise.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mise.data.Names
import app.mise.data.PlanRepo
import kotlinx.coroutines.launch

/**
 * Pantry, first step: the staples Mise assumes you have (salt, oil, everyday spices). They stay
 * off the list, under "Usually at home". Switch one off and it goes on the list whenever a dish
 * needs it. Have, low and out arrive with the full pantry.
 */
@Composable
fun PantryScreen(onSettings: () -> Unit) {
    val c = Mise.colours
    val repo = PlanRepo.get(LocalContext.current)
    val staples by remember { repo.staples() }.collectAsState(initial = null)
    var add by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        val list = staples
        ScreenHeader(
            eyebrow = if (list == null) "At home" else "${list.count { it.second }} staples",
            title = "Pantry",
            actionIcon = MiseIcons.Settings, actionLabel = "Settings", onAction = onSettings,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item(key = "intro") {
                Text(
                    "Staples are things you always keep. They stay off the grocery list (you will find them under Usually at home) until you switch one off here.",
                    style = Mise.type.bodyS, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiseField(add, { add = it }, "Add a staple, e.g. Jaggery", Modifier.weight(1f), imeAction = ImeAction.Done, onIme = {
                        val n = add.trim()
                        if (n.isNotEmpty()) {
                            repo.scope.launch { repo.setStaple(Names.keyOf(n), n, true) }
                            Toasts.show("$n is a staple")
                            add = ""
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("Add", {
                        val n = add.trim()
                        if (n.isNotEmpty()) {
                            repo.scope.launch { repo.setStaple(Names.keyOf(n), n, true) }
                            Toasts.show("$n is a staple")
                            add = ""
                        }
                    }, enabled = add.isNotBlank())
                }
                SectionLabel("Staples", list?.size?.toString())
            }
            val rows = list.orEmpty()
            itemsIndexed(rows, key = { _, it -> it.first.lowercase() }) { i, (name, on) ->
                Column(Modifier.fillMaxWidth().joinedCell(i == 0, i == rows.lastIndex, c.surface, c.line)) {
                    if (i > 0) Divider(Modifier.padding(start = 16.dp))
                    MiseSwitch(on, { v ->
                        repo.scope.launch { repo.setStaple(Names.keyOf(name), name, v) }
                    }, name, sub = if (on) "Kept at home" else "Goes on the list")
                }
            }
            if (list != null && list.isEmpty()) item(key = "empty") {
                EmptyState("No staples yet", "Plan a dish and its everyday spices and oils appear here.")
            }
        }
    }
}
