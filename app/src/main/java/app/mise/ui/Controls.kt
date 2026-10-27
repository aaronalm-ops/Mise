package app.mise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mise.data.Measure

private val FieldShape = RoundedCornerShape(12.dp)
val PillShape = RoundedCornerShape(50)

/** Text field in the Stone style: white surface, hairline border, placeholder in grey. */
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
) {
    val c = Mise.colours
    val style = (if (big) Mise.type.displayS else Mise.type.body).copy(color = c.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(c.text),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = modifier.semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight.dp)
                    .background(c.surface, FieldShape)
                    .border(BorderStroke(1.dp, c.line), FieldShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.text2))
                inner()
            }
        },
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Mise.colours
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(if (enabled) c.control else c.line, PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Mise.type.bodyM, color = if (enabled) c.onControl else c.text2)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val c = Mise.colours
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(c.surface, PillShape)
            .border(BorderStroke(1.dp, c.line), PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Mise.type.bodyM, color = if (danger) c.danger else c.text)
    }
}

/** "+ Add ingredient" style row button. */
@Composable
fun AddRow(text: String, onClick: () -> Unit) {
    val c = Mise.colours
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MiseIcons.Create, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Mise.type.bodyM, color = c.text)
    }
}

@Composable
fun MiseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Mise.colours
    Box(
        Modifier
            .defaultMinSize(minHeight = 40.dp)
            .background(if (selected) c.control else c.surface, PillShape)
            .border(BorderStroke(1.dp, if (selected) c.control else c.line), PillShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = Mise.type.label, color = if (selected) c.onControl else c.text)
    }
}

/** Minus, number, plus. 48 dp targets, mono number. */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 24, label: String) {
    val c = Mise.colours
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(48.dp).background(c.soft, shape).border(BorderStroke(1.dp, c.line), shape)
                .clickable(enabled = value > min, role = Role.Button, onClickLabel = "Fewer $label") { onChange(value - 1) },
            contentAlignment = Alignment.Center,
        ) { Text("−", style = Mise.type.displayS, color = if (value > min) c.text else c.line) }
        Text(value.toString(), style = Mise.type.number.copy(fontSize = Mise.type.displayS.fontSize), color = c.text, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(
            Modifier.size(48.dp).background(c.soft, shape).border(BorderStroke(1.dp, c.line), shape)
                .clickable(enabled = value < max, role = Role.Button, onClickLabel = "More $label") { onChange(value + 1) },
            contentAlignment = Alignment.Center,
        ) { Text("+", style = Mise.type.displayS, color = if (value < max) c.text else c.line) }
    }
}

/** Unit selector: tap to open the list of units. */
@Composable
fun UnitPicker(unitKey: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Mise.colours
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .fillMaxWidth()
                .background(c.soft, FieldShape)
                .border(BorderStroke(1.dp, c.line), FieldShape)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose unit") { open = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(Measure.from(unitKey).label, style = Mise.type.label, color = c.text, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            Measure.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label, style = Mise.type.body, color = c.text) },
                    onClick = { onPick(m.key); open = false },
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
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Mise.colours.line))
}
