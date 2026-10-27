package app.mise.ui

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mise.R

/**
 * Matte Stone, chosen by Aaron on 26 October 2026. Graphite on warm off-white by day, silver on
 * true black by night, a fine grain on filled controls, no gloss, no gradients and no colour
 * accent. Colour in the app comes from food photos only; cyan appears only in the maker mark.
 * Every colour a composable uses comes from [Mise.colours]; hex literals live only in this file.
 */
@Immutable
data class MiseColours(
    val base: Color,
    val surface: Color,
    val soft: Color,
    val text: Color,
    val text2: Color,
    val line: Color,
    /** Primary filled control (Build grocery list, selected chips): charcoal by day, off-white by night. */
    val control: Color,
    val onControl: Color,
    /** The matte Stone fill: selected navigation disc and the Add action. */
    val disc: Color,
    val onDisc: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightColours = MiseColours(
    base = Color(0xFFF1F0ED),
    surface = Color(0xFFFFFFFF),
    soft = Color(0xFFF3F4F6),
    text = Color(0xFF111111),
    text2 = Color(0xFF5F646A),
    line = Color(0xFFDDDFE2),
    control = Color(0xFF18191B),
    onControl = Color(0xFFF4F6F8),
    disc = Color(0xFF3A3D41),
    onDisc = Color(0xFFF4F6F8),
    danger = Color(0xFFB42318),
    isDark = false,
)

val DarkColours = MiseColours(
    base = Color(0xFF000000),
    surface = Color(0xFF111111),
    soft = Color(0xFF1A1A1A),
    text = Color(0xFFF5F5F5),
    text2 = Color(0xFF8C8C8C),
    line = Color(0xFF262626),
    control = Color(0xFFF4F6F8),
    onControl = Color(0xFF18191B),
    disc = Color(0xFFC7CBD1),
    onDisc = Color(0xFF18191B),
    danger = Color(0xFFE25C5C),
    isDark = true,
)

val Manrope = FontFamily(
    Font(R.font.manrope, weight = FontWeight.W500),
    Font(R.font.manrope, weight = FontWeight.W600),
)
val Inter = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal),
    Font(R.font.inter, weight = FontWeight.W500),
    Font(R.font.inter, weight = FontWeight.W600),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono, weight = FontWeight.W500),
)

/** Type scale shared with Lumen: Manrope titles, Inter body, JetBrains Mono labels and numbers. */
@Immutable
data class MiseType(
    val displayM: TextStyle,
    val displayS: TextStyle,
    val body: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
    val credit: TextStyle,
    val label: TextStyle,
    val labelS: TextStyle,
    val nav: TextStyle,
    val number: TextStyle,
)

val MiseTypography = MiseType(
    displayM = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em),
    displayS = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodyM = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 20.sp),
    bodyS = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    credit = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
    labelS = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.14.em),
    nav = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 16.sp),
    number = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 18.sp),
)

private val LocalMiseColours = staticCompositionLocalOf { LightColours }
private val LocalMiseType = staticCompositionLocalOf { MiseTypography }

object Mise {
    val colours: MiseColours
        @Composable get() = LocalMiseColours.current
    val type: MiseType
        @Composable get() = LocalMiseType.current
}

@Composable
fun MiseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColours else LightColours
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.control, onPrimary = c.onControl,
            background = c.base, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.text2,
            surfaceContainerLow = c.surface, outline = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.control, onPrimary = c.onControl,
            background = c.base, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.text2,
            surfaceContainerLow = c.surface, outline = c.line, error = c.danger,
        )
    }
    CompositionLocalProvider(LocalMiseColours provides c, LocalMiseType provides MiseTypography) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** A 64 px tile of soft monochrome noise, generated once, tiled for the matte grain. */
private val GrainTile: ImageBitmap by lazy {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = java.util.Random(26)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val v = random.nextInt(256)
            bmp.setPixel(x, y, android.graphics.Color.argb(random.nextInt(56), v, v, v))
        }
    }
    bmp.asImageBitmap()
}

/**
 * The matte finish: a fine grain drawn over a filled shape (place it after the background).
 * Static, so it costs nothing per frame and never moves.
 */
fun Modifier.matteGrain(shape: Shape): Modifier = this.drawWithCache {
    val brush = ShaderBrush(ImageShader(GrainTile, TileMode.Repeated, TileMode.Repeated))
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawWithContent {
        drawOutline(outline, brush, alpha = 0.6f)
        drawContent()
    }
}
