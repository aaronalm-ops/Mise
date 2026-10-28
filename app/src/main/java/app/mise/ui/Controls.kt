package app.mise.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.data.Measure

private val FieldShape = RoundedCornerShape(12.dp)
val PillShape = RoundedCornerShape(50)

/** Text field in the Stone style: white surface, hairline border that darkens on focus. */
@Composable
fun MiseField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    big: Boolean = false,
    minHeight: Int = 48,
    imeAction: ImeAction? = null,
    onIme: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    error: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    compact: Boolean = false,
) {
    val c = Mise.colours
    var focused by remember { mutableStateOf(false) }
    val style = (if (big) Mise.type.displayS else Mise.type.body).copy(color = c.text, textAlign = textAlign)
    val action = imeAction ?: if (singleLine) ImeAction.Next else ImeAction.Default
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(c.text),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = action),
        keyboardActions = if (onIme != null) KeyboardActions(onAny = { onIme() }) else KeyboardActions.Default,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocus?.invoke(it.isFocused) }
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight.dp)
                    .background(c.surface, FieldShape)
                    .border(BorderStroke(if (focused) 1.5.dp else 1.dp, if (error) c.danger else if (focused) c.text2 else c.line), FieldShape)
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.text2), maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                inner()
            }
        },
    )
}

/** The main action of a screen: matte Stone pill with grain. Says why when disabled. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Mise.colours
    Box(
        modifier
            .heightIn(min = 52.dp)
            .then(
                if (enabled) Modifier.background(c.disc, PillShape).matteGrain(PillShape)
                else Modifier.background(c.soft, PillShape).border(BorderStroke(1.dp, c.line), PillShape),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Mise.type.bodyM, color = if (enabled) c.onDisc else c.text2, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true) {
    val c = Mise.colours
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(c.surface, PillShape)
            .border(BorderStroke(1.dp, c.line), PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Mise.type.bodyM, color = if (!enabled) c.text2 else if (danger) c.danger else c.text, textAlign = TextAlign.Center)
    }
}

/** Plain text action, for secondary choices beside a main button. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val c = Mise.colours
    Box(
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Mise.type.bodyM, color = if (danger) c.danger else c.text)
    }
}

/** "+ Add ingredient" style row button. */
@Composable
fun AddRow(text: String, modifier: Modifier = Modifier, muted: Boolean = false, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiseIcons.Create, contentDescription = null, tint = if (muted) c.text2 else c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Mise.type.bodyM, color = if (muted) c.text2 else c.text)
    }
}

/** A choice chip. Selected is filled graphite (silver at night); the count sits after the label. */
@Composable
fun MiseChip(label: String, selected: Boolean, count: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Mise.colours
    CappedText(1.25f) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 40.dp)
                .background(if (selected) c.control else c.surface, PillShape)
                .border(BorderStroke(1.dp, if (selected) c.control else c.line), PillShape)
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = when {
                selected -> c.onControl
                !enabled -> c.line
                else -> c.text
            }
            Text(label.uppercase(), style = Mise.type.label, color = fg, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = Mise.type.label, color = if (selected) c.onControl.copy(alpha = 0.7f) else c.text2, maxLines = 1)
            }
        }
    }
}

/** A row of chips that scrolls sideways, with the screen gutter kept at both ends. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Minus, number, plus. 48 dp targets; the end you cannot go past is clearly greyed. */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 24, label: String, display: String? = null) {
    val c = Mise.colours
    val shape = RoundedCornerShape(12.dp)
    CappedText(1.3f) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton("−", value > min, "Fewer $label", shape) { onChange(value - 1) }
            Text(
                display ?: value.toString(),
                style = Mise.type.number.copy(fontSize = Mise.type.displayS.fontSize),
                color = c.text,
                modifier = Modifier.widthIn(min = 44.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            StepButton("+", value < max, "More $label", shape) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, label: String, shape: RoundedCornerShape, onClick: () -> Unit) {
    val c = Mise.colours
    Box(
        Modifier.size(48.dp)
            .background(if (enabled) c.soft else c.surface, shape)
            .border(BorderStroke(1.dp, c.line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = Mise.type.displayS, color = if (enabled) c.text else c.line) }
}

/** An on/off switch drawn in Stone: a graphite track with a matte thumb. */
@Composable
fun MiseSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, sub: String? = null, inset: androidx.compose.ui.unit.Dp = 16.dp) {
    val c = Mise.colours
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(160), label = "switch")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, style = Mise.type.body, color = c.text)
            if (sub != null) Text(sub, style = Mise.type.bodyS, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(width = 46.dp, height = 26.dp)
                .background(if (checked) c.control else c.soft, PillShape)
                .border(BorderStroke(1.dp, if (checked) c.control else c.line), PillShape)
                .padding(3.dp),
        ) {
            Box(Modifier.offset(x = x).size(20.dp).background(if (checked) c.onControl else c.text2, CircleShape))
        }
    }
}

/** Unit selector: tap for the list. Shows "unit" in grey until one is chosen. */
@Composable
fun UnitPicker(unitKey: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, error: Boolean = false) {
    val c = Mise.colours
    var open by remember { mutableStateOf(false) }
    val m = Measure.from(unitKey)
    Box(modifier) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .fillMaxWidth()
                .background(c.soft, FieldShape)
                .border(BorderStroke(1.dp, if (error) c.danger else c.line), FieldShape)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose unit") { open = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CappedText(1.2f) {
                Text(m.label, style = Mise.type.label, color = if (m == Measure.NONE) c.text2 else c.text, maxLines = 1)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            Measure.choices.forEach { u ->
                DropdownMenuItem(
                    text = { Text(u.label, style = Mise.type.body, color = c.text) },
                    onClick = { onPick(u.key); open = false },
                )
            }
        }
    }
}

/** Two-choice confirmation in the Stone style. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    val c = Mise.colours
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = Mise.type.displayS, color = c.text) },
        text = { Text(body, style = Mise.type.body, color = c.text2) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, style = Mise.type.bodyM, color = if (danger) c.danger else c.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismiss, style = Mise.type.bodyM, color = c.text) }
        },
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Mise.colours.line))
}
