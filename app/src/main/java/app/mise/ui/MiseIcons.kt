package app.mise.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons on the approved Instinct grid (24 dp, 2 dp stroke, round caps and joins). Calendar,
 * Library, List, Create, Settings and Search are the approved drawings from
 * Aaron Almeida Brand/official/ui/navigation-v1; Pantry (a storage jar), Back and Close are
 * Mise additions drawn on the same rules. The black is replaced by the Icon tint.
 */
object MiseIcons {
    private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        strokes.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        fills.forEach { d -> b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        return b.build()
    }

    val Calendar: ImageVector by lazy {
        icon("calendar", listOf("M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z", "M8 3v4M16 3v4M4 10h16M8 14h2M14 14h2"))
    }
    val Library: ImageVector by lazy {
        icon("library", listOf("M12 6.5C9.5 4.8 6.5 4.4 3.5 5.5V18c3-1.1 6-.7 8.5 1 2.5-1.7 5.5-2.1 8.5-1V5.5C17.5 4.4 14.5 4.8 12 6.5Zm0 0V19"))
    }
    val List: ImageVector by lazy {
        icon("list", listOf("M10 8h9M10 16h7"), listOf("M4 8a1 1 0 1 0 2 0a1 1 0 1 0-2 0Z", "M4 16a1 1 0 1 0 2 0a1 1 0 1 0-2 0Z"))
    }
    val Pantry: ImageVector by lazy {
        icon("pantry", listOf("M8.5 4h7", "M8 7h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Z", "M9.5 12.5h5"))
    }
    val Create: ImageVector by lazy { icon("create", listOf("M12 5v14M5 12h14")) }
    val Settings: ImageVector by lazy {
        icon("settings", listOf("M4 7h6M16 7h4M4 17h3M13 17h7", "M10 7a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z", "M7 17a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z"))
    }
    val Search: ImageVector by lazy {
        icon("search", listOf("M10 4.5h2a5.5 5.5 0 0 1 5.5 5.5v2a5.5 5.5 0 0 1-5.5 5.5h-2A5.5 5.5 0 0 1 4.5 12v-2A5.5 5.5 0 0 1 10 4.5ZM16.5 16.5l4 4"))
    }
    val Back: ImageVector by lazy { icon("back", listOf("M15 5l-7 7 7 7")) }
    val Edit: ImageVector by lazy { icon("edit", listOf("M5 19h4L19 9l-4-4L5 15v4Z", "M13.5 6.5l4 4")) }
    val Forward: ImageVector by lazy { icon("forward", listOf("M9 5l7 7-7 7")) }
    val Check: ImageVector by lazy { icon("check", listOf("M5 12.5l4.5 4.5L19 7.5")) }
    val Arrow: ImageVector by lazy { icon("arrow", listOf("M5 12h14M13 6l6 6-6 6")) }
    val Close: ImageVector by lazy { icon("close", listOf("M6 6l12 12M18 6L6 18")) }
    val Camera: ImageVector by lazy {
        icon("camera", listOf("M5 8h3l1.5-2h5L16 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z"))
    }
    val More: ImageVector by lazy {
        icon("more", emptyList(), listOf("M5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0Z", "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0Z", "M16 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0Z"))
    }
    val Handle: ImageVector by lazy { icon("handle", listOf("M6 9h12M6 15h12")) }
    val Cook: ImageVector by lazy {
        icon("cook", listOf("M4 11h16v2a6 6 0 0 1-6 6h-4a6 6 0 0 1-6-6v-2Z", "M2 11h2M20 11h2", "M9 4.5c0 1.2 1 1.3 1 2.5M13.5 4.5c0 1.2 1 1.3 1 2.5"))
    }
    val Share: ImageVector by lazy { icon("share", listOf("M12 4v11M8 8l4-4 4 4", "M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")) }
    val Repeat: ImageVector by lazy { icon("repeat", listOf("M5 10a5 5 0 0 1 5-5h7M14 2l3 3-3 3", "M19 14a5 5 0 0 1-5 5H7M10 22l-3-3 3-3")) }
    /** Mise AI: a four-point spark. */
    val Spark: ImageVector by lazy { icon("spark", listOf("M12 3c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7Z", "M19 16v4M17 18h4")) }
}
