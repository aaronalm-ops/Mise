package app.mise.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class Dest(val label: String, val icon: () -> ImageVector) {
    Plan("Plan", { MiseIcons.Calendar }),
    Recipes("Recipes", { MiseIcons.Library }),
    List("List", { MiseIcons.List }),
    Pantry("Pantry", { MiseIcons.Pantry }),
}

/**
 * The approved Instinct lifted navigation (v2): four destinations and the centre Add action.
 * Selected disc 48 dp, rises 12 dp in 300 ms (ease-out), 4 dp ring in the bar colour, labels
 * always visible. Colour and fill switch immediately; only the position animates. Compose scales
 * the animation with the phone's animation setting, so "Remove animations" gives the final
 * state at once. Mise fills the disc in matte Stone instead of a gradient.
 */
@Composable
fun MiseNavBar(current: Dest, onSelect: (Dest) -> Unit, onAdd: () -> Unit) {
    val c = Mise.colours
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            NavItem(Dest.Plan, current == Dest.Plan) { onSelect(Dest.Plan) }
            NavItem(Dest.Recipes, current == Dest.Recipes) { onSelect(Dest.Recipes) }
            AddItem(onAdd)
            NavItem(Dest.List, current == Dest.List) { onSelect(Dest.List) }
            NavItem(Dest.Pantry, current == Dest.Pantry) { onSelect(Dest.Pantry) }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun RowScope.NavItem(dest: Dest, selected: Boolean, onClick: () -> Unit) {
    val c = Mise.colours
    val lift by animateDpAsState(
        targetValue = if (selected) (-12).dp else 0.dp,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "navLift",
    )
    val ring = c.surface
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = lift)
                .size(48.dp)
                .then(
                    if (selected) {
                        Modifier
                            .drawBehind { drawCircle(ring, radius = size.minDimension / 2f + 4.dp.toPx()) }
                            .shadow(
                                elevation = if (c.isDark) 0.dp else 6.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.18f),
                                spotColor = Color.Black.copy(alpha = 0.18f),
                            )
                            .background(c.disc, CircleShape)
                            .matteGrain(CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = dest.icon(),
                contentDescription = null,
                tint = if (selected) c.onDisc else c.text2,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
            CappedText(1.15f) {
                Text(text = dest.label, style = Mise.type.nav, color = if (selected) c.text else c.text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RowScope.AddItem(onAdd: () -> Unit) {
    val c = Mise.colours
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Button, onClickLabel = "Add", onClick = onAdd)
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            Modifier.size(36.dp).background(c.disc, shape).matteGrain(shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MiseIcons.Create, contentDescription = null, tint = c.onDisc, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        CappedText(1.15f) { Text("Add", style = Mise.type.nav, color = c.text, maxLines = 1) }
    }
}
