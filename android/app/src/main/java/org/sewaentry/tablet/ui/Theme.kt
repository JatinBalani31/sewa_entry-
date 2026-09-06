package org.sewaentry.tablet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The same navy the web console uses, so the tablet and the admin console
// read as one system.
val Navy = Color(0xFF1F3A5F)
val NavyDeep = Color(0xFF16293F)

val ArrivalGreen = Color(0xFF177245)
val ArrivalGreenBg = Color(0xFFE3F5EA)
val DepartureRed = Color(0xFFA52834)
val DepartureRedBg = Color(0xFFFBE5E7)
val Amber = Color(0xFF8A5C06)
val AmberBg = Color(0xFFFDF3D8)

val Surface = Color(0xFFFFFFFF)
val SurfaceAlt = Color(0xFFF8FAFC)
val Background = Color(0xFFF4F6FA)
val Line = Color(0xFFDFE4EC)
val Ink = Color(0xFF16202E)
val InkMuted = Color(0xFF55637A)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = ArrivalGreen,
    background = Background,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = InkMuted,
    outline = Line,
    error = DepartureRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC1F0),
    background = Color(0xFF10161F),
    surface = Color(0xFF18202C),
)

@Composable
fun SewaTheme(
    // The tablet lives under hall lighting and is read at arm's length; the
    // light palette is the one that works there, so dark mode is opt-in only.
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
