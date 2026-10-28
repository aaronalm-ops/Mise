package app.mise.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mise.data.Choices
import app.mise.data.Recipe
import app.mise.data.RecipeRepo
import app.mise.data.Scaling

/**
 * Cook mode: larger type, the screen stays on, and a tap strikes off an ingredient or a step so
 * you never lose your place with floury hands. Ticks survive rotation and leaving the app.
 */
@Composable
fun CookModeScreen(id: Long, servings: Int?, choices: List<String>?, onClose: () -> Unit) {
    val c = Mise.colours
    val repo = RecipeRepo.get(LocalContext.current)
    val state by remember(id) { repo.observe(id) }.collectAsState(initial = Unit)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var doneIngs by rememberSaveable(id) { mutableStateOf(setOf<Int>()) }
    var doneSteps by rememberSaveable(id) { mutableStateOf(setOf<Int>()) }
    val tick = Haptic.rememberTick()

    Column(Modifier.fillMaxSize()) {
        val r = state as? Recipe
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(MiseIcons.Close, "Close cook mode", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("COOKING · SCREEN STAYS ON", style = Mise.type.labelS, color = c.text2)
                Text(r?.title ?: "", style = Mise.type.displayS, color = c.text, maxLines = 2)
            }
        }
        if (r == null) return@Column
        val serv = servings ?: r.baseServings
        val picked = Choices.decode(choices?.let { Choices.encode(it) } ?: "", r)
        val ings = Choices.options(r, picked).flatMap { it.ingredients } + r.ingredients
        val big = Mise.type.body.copy(fontSize = 18.sp, lineHeight = 26.sp)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                SectionLabel("Ingredients · $serv servings", "${doneIngs.size}/${ings.size}")
            }
            itemsIndexed(ings) { i, ing ->
                val done = i in doneIngs
                CookRow(done, onToggle = { tick(!done); doneIngs = if (done) doneIngs - i else doneIngs + i }) {
                    Column(Modifier.weight(1f)) {
                        Text(ing.name, style = big, color = if (done) c.text2 else c.text, textDecoration = if (done) TextDecoration.LineThrough else null)
                        if (ing.note.isNotBlank()) Text(ing.note, style = Mise.type.bodyS, color = c.text2)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Scaling.scaledText(ing.qty, ing.unit, r.baseServings, serv, ing.name, r.exact),
                        style = Mise.type.number.copy(fontSize = 17.sp), color = if (done) c.text2 else c.text,
                    )
                }
            }
            if (r.steps.isNotEmpty()) {
                item { SectionLabel("Steps", "${doneSteps.size}/${r.steps.size}") }
                itemsIndexed(r.steps) { i, step ->
                    val done = i in doneSteps
                    CookRow(done, onToggle = { tick(!done); doneSteps = if (done) doneSteps - i else doneSteps + i }) {
                        Text("${i + 1}".padStart(2, '0'), style = Mise.type.label, color = c.text2, modifier = Modifier.width(32.dp))
                        Text(step, style = big, color = if (done) c.text2 else c.text, textDecoration = if (done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (r.notes.isNotBlank()) {
                item {
                    SectionLabel("Notes")
                    Card { Text(r.notes, style = big, color = c.text, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            PrimaryButton("Done cooking", onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CookRow(done: Boolean, onToggle: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Mise.colours
    val ring by animateColorAsState(if (done) c.control else c.line, label = "ring")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).border(BorderStroke(1.5.dp, ring), CircleShape)
                .then(if (done) Modifier.background(c.control, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(MiseIcons.Check, contentDescription = null, tint = c.onControl, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        content()
    }
    Divider()
}
