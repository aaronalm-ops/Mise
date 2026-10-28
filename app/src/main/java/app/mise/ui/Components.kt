package app.mise.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

val CardShape = RoundedCornerShape(14.dp)
val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** Screen header: mono eyebrow, Manrope title, optional trailing content (icon tiles, pills). */
@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Mise.colours
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
        if (leading != null) {
            leading()
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (eyebrow.isNotEmpty()) Text(eyebrow.uppercase(), style = Mise.type.labelS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, style = Mise.type.displayM, color = c.text, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailing() }
            }
            if (actionIcon != null && onAction != null) {
                if (trailing != null) Spacer(Modifier.width(8.dp))
                IconTile(actionIcon, actionLabel ?: "", onClick = onAction)
            }
        }
    }
}

/** The 48 dp bordered icon tile used in headers (settings, back, add item). */
@Composable
fun IconTile(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Mise.colours
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(48.dp)
            .padding(2.dp)
            .background(c.surface, shape)
            .border(BorderStroke(1.dp, c.line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) c.text else c.line, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val c = Mise.colours
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp)) {
        Text(text.uppercase(), style = Mise.type.label, color = c.text2, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing.uppercase(), style = Mise.type.label, color = c.text2)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Mise.colours
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface, CardShape)
            .border(BorderStroke(1.dp, c.line), CardShape),
        content = content,
    )
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

/** A small rounded label: LIVE, OFFLINE, TO CHECK, ADDED. */
@Composable
fun Pill(text: String, filled: Boolean = false, dot: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Mise.colours
    Row(
        Modifier
            .background(if (filled) c.control else c.surface, PillShape)
            .border(BorderStroke(1.dp, if (filled) c.control else c.line), PillShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).background(if (filled) c.onControl else c.text, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), style = Mise.type.labelS, color = if (filled) c.onControl else c.text, maxLines = 1)
    }
}

/**
 * Caps how far the phone's font size can grow inside tight rows (navigation, day strip,
 * steppers), so large text stays readable without clipping. Body text elsewhere scales fully.
 */
@Composable
fun CappedText(max: Float = 1.3f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(d.density, max)) { content() }
    }
}

/** Haptic ticks for the moments that matter: ticking an item, adding a dish, a long press. */
object Haptic {
    @Composable
    fun rememberTick(): (Boolean) -> Unit {
        val h = LocalHapticFeedback.current
        return { on -> h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
    }

    @Composable
    fun rememberConfirm(): () -> Unit {
        val h = LocalHapticFeedback.current
        return { h.performHapticFeedback(HapticFeedbackType.Confirm) }
    }

    @Composable
    fun rememberLongPress(): () -> Unit {
        val h = LocalHapticFeedback.current
        return { h.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/** One short message with an optional Undo, shown above the navigation bar for four seconds. */
data class Toast(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

object Toasts {
    var current by mutableStateOf<Toast?>(null)
        private set

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        current = Toast(text, action, onAction)
    }

    fun undo(text: String, onUndo: () -> Unit) = show(text, "Undo", onUndo)

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val c = Mise.colours
    val t = Toasts.current
    LaunchedEffect(t?.id) {
        val id = t?.id ?: return@LaunchedEffect
        delay(if (t.action != null) 4500 else 2500)
        Toasts.dismiss(id)
    }
    AnimatedVisibility(
        visible = t != null,
        enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180)),
        modifier = modifier,
    ) {
        val shown = t ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(min = 52.dp)
                .background(c.disc, shape)
                .matteGrain(shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown.text, style = Mise.type.body, color = c.onDisc, modifier = Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (shown.action != null) {
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            Toasts.dismiss(shown.id)
                            shown.onAction?.invoke()
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(shown.action.uppercase(), style = Mise.type.label, color = c.onDisc)
                }
            }
        }
    }
}

/** Bottom sheet in the Panels v2 frame: eyebrow, task heading, reachable Close, then content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiseSheet(
    title: String,
    onDismiss: () -> Unit,
    eyebrow: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mise.colours
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.text,
        shape = SheetShape,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).background(c.line, RoundedCornerShape(2.dp)))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (!eyebrow.isNullOrBlank()) Text(eyebrow.uppercase(), style = Mise.type.labelS, color = c.text2)
                    Text(title, style = Mise.type.displayS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconTile(MiseIcons.Close, "Close", onClick = onDismiss)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Recipe tile until recipes have photos: a light stone square with two letters. */
@Composable
fun Monogram(title: String, size: Int = 44) {
    val c = Mise.colours
    val shape = RoundedCornerShape(10.dp)
    val letters = title.split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size.dp).background(c.soft, shape).border(BorderStroke(1.dp, c.line), shape).matteGrain(shape),
        contentAlignment = Alignment.Center,
    ) {
        CappedText(1.2f) { Text(letters, style = Mise.type.label, color = c.text) }
    }
}

/** "3 weeks ago", "yesterday", from an ISO date. */
fun agoText(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val d = runCatching { java.time.LocalDate.parse(iso) }.getOrNull() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, java.time.LocalDate.now())
    return when {
        days < 0 -> "planned"
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "last week"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
}

/**
 * Draws one row of a card made of separate list items (so long lists can be lazy): the first
 * row has the rounded top, the last the rounded bottom, with one hairline around the whole card.
 */
fun Modifier.joinedCell(first: Boolean, last: Boolean, fill: androidx.compose.ui.graphics.Color, line: androidx.compose.ui.graphics.Color): Modifier =
    this.drawBehind {
        val r = 14.dp.toPx()
        val w = 1.dp.toPx()
        val h = w / 2
        val top = if (first) androidx.compose.ui.geometry.CornerRadius(r) else androidx.compose.ui.geometry.CornerRadius.Zero
        val bottom = if (last) androidx.compose.ui.geometry.CornerRadius(r) else androidx.compose.ui.geometry.CornerRadius.Zero
        val rect = androidx.compose.ui.geometry.RoundRect(
            left = h, top = if (first) h else -h, right = size.width - h, bottom = if (last) size.height - h else size.height + h,
            topLeftCornerRadius = top, topRightCornerRadius = top, bottomRightCornerRadius = bottom, bottomLeftCornerRadius = bottom,
        )
        val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(rect) }
        drawPath(path, fill)
        clipRect(0f, 0f, size.width, size.height) {
            drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
        }
    }
