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
    val Close: ImageVector by lazy { icon("close", listOf("M6 6l12 12M18 6L6 18")) }
    val Camera: ImageVector by lazy {
        icon("camera", listOf("M5 8h3l1.5-2h5L16 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z"))
    }
}
