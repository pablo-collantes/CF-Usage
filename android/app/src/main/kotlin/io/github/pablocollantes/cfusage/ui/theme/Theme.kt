package io.github.pablocollantes.cfusage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** shadcn/ui "zinc" tokens. Material3 is only the primitive layer; screens read these. */
@Immutable
data class CfColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val border: Color,
    val muted: Color,
    val mutedForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val accent: Color,
    val destructive: Color,
    val ring: Color,
    val chart1: Color = Palette.Blue,
    val chart2: Color = Palette.Green,
    val chart3: Color = Palette.Amber,
    val chart4: Color = Palette.Red,
    val chart5: Color = Palette.Violet,
    val dark: Boolean,
)

object Palette {
    val Blue = Color(0xFF2563EB)
    val Green = Color(0xFF16A34A)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFDC2626)
    val Violet = Color(0xFF8B5CF6)
    val Zinc400 = Color(0xFFA1A1AA)
}

val LightColors = CfColors(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF09090B),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFE4E4E7),
    muted = Color(0xFFF4F4F5),
    mutedForeground = Color(0xFF71717A),
    primary = Color(0xFF18181B),
    primaryForeground = Color(0xFFFAFAFA),
    secondary = Color(0xFFF4F4F5),
    accent = Color(0xFFF4F4F5),
    destructive = Color(0xFFDC2626),
    ring = Color(0xFFA1A1AA),
    dark = false,
)

val DarkColors = CfColors(
    background = Color(0xFF09090B),
    foreground = Color(0xFFFAFAFA),
    card = Color(0xFF09090B),
    border = Color(0xFF27272A),
    muted = Color(0xFF27272A),
    mutedForeground = Color(0xFFA1A1AA),
    primary = Color(0xFFFAFAFA),
    primaryForeground = Color(0xFF18181B),
    secondary = Color(0xFF27272A),
    accent = Color(0xFF27272A),
    destructive = Color(0xFFEF4444),
    ring = Color(0xFF52525B),
    chart1 = Color(0xFF3B82F6),
    chart2 = Color(0xFF22C55E),
    chart3 = Color(0xFFFBBF24),
    chart4 = Color(0xFFEF4444),
    chart5 = Color(0xFFA78BFA),
    dark = true,
)

val LocalCfColors = staticCompositionLocalOf { LightColors }

/** Figures line up in columns: tabular numerals everywhere a number is shown. */
private const val TNUM = "tnum"

@Immutable
data class CfType(
    val h1: TextStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, fontFeatureSettings = TNUM),
    val h2: TextStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    val title: TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    val small: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val micro: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    val figure: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM),
    val bigFigure: TextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, fontFeatureSettings = TNUM),
    val mono: TextStyle = TextStyle(fontSize = 12.sp, fontFeatureSettings = TNUM, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
)

val LocalCfType = staticCompositionLocalOf { CfType() }

object Cf {
    val colors: CfColors @Composable get() = LocalCfColors.current
    val type: CfType @Composable get() = LocalCfType.current
}

@Composable
fun CfUsageTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.card, onSurface = c.foreground, surfaceVariant = c.muted, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerLow = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.muted,
            outline = c.border, outlineVariant = c.border, error = c.destructive, secondaryContainer = c.muted,
            onSecondaryContainer = c.foreground,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.card, onSurface = c.foreground, surfaceVariant = c.muted, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerLow = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.muted,
            outline = c.border, outlineVariant = c.border, error = c.destructive, secondaryContainer = c.muted,
            onSecondaryContainer = c.foreground,
        )
    }
    val type = CfType()
    CompositionLocalProvider(LocalCfColors provides c, LocalCfType provides type) {
        MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = type.body, bodyMedium = type.body), content = content)
    }
}

/** The color for an API status / severity string. */
@Composable
fun statusColor(status: String): Color {
    val c = Cf.colors
    return when (status) {
        "over", "critical" -> c.chart4
        "warn" -> c.chart3
        "info" -> c.chart1
        "ok" -> c.chart2
        else -> c.mutedForeground
    }
}

fun statusLabel(status: String) = when (status) {
    "over" -> "Over"
    "critical" -> "Critical"
    "warn" -> "Warning"
    "info" -> "Watch"
    "ok" -> "OK"
    else -> status.replaceFirstChar { it.uppercase() }
}
