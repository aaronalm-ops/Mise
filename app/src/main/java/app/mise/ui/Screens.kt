package app.mise.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Scrollable screen body with the 16 dp gutter. */
@Composable
private fun ScreenBody(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) { content() }
}

@Composable
fun PlanScreen(onSettings: () -> Unit) {
    val c = Mise.colours
    val today = remember { LocalDate.now() }
    val monday = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val days = remember(monday) { (0..6).map { monday.plusDays(it.toLong()) } }
    var selected by rememberSaveable { mutableIntStateOf(days.indexOf(today).coerceAtLeast(0)) }
    val weekLabel = "Week of " + monday.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(weekLabel, "This week", MiseIcons.Settings, "Settings", onSettings)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, d ->
                val isSel = i == selected
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .height(58.dp)
                        .background(if (isSel) c.control else c.surface, shape)
                        .border(BorderStroke(1.dp, if (isSel) c.control else c.line), shape)
                        .selectable(selected = isSel, role = Role.Tab, onClick = { selected = i }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(),
                        style = Mise.type.labelS,
                        color = if (isSel) c.onControl else c.text2,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(d.dayOfMonth.toString(), style = Mise.type.number, color = if (isSel) c.onControl else c.text)
                }
            }
        }
        ScreenBody {
            val day = days[selected]
            SectionLabel(day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK), "0 dishes")
            EmptyState(
                title = "Nothing planned yet",
                body = "Planning arrives once recipes are in. Next update: add your own recipes, then put them on days.",
            )
        }
    }
}

@Composable
fun ListScreen(onSettings: () -> Unit) {
    val c = Mise.colours
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Shared with your household", "Grocery list", MiseIcons.Settings, "Settings", onSettings)
        ScreenBody {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("0", style = Mise.type.number, color = c.text)
                        Text(" items left", style = Mise.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
                    }
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(c.line, RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            EmptyState(
                title = "Your list is empty",
                body = "Plan some dishes and the list builds itself, merged by aisle.",
            )
        }
    }
}

@Composable
fun PantryScreen(onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("At home", "Pantry", MiseIcons.Settings, "Settings", onSettings)
        ScreenBody {
            SectionLabel("Staples", "hidden from list when have")
            EmptyState(
                title = "Nothing here yet",
                body = "Salt, oil and spices will live here as have, low or out, so the list only asks for what you need.",
            )
        }
    }
}
