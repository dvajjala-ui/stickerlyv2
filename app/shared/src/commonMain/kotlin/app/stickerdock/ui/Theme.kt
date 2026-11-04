package app.stickerdock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Brand {
    val Ink = Color(0xFF12130F)
    val InkSoft = Color(0xFF2A2B26)
    val Volt = Color(0xFFD9FF6A)
    val VoltDeep = Color(0xFF4E6B00)
    val Paper = Color(0xFFF7F6F1)
    val Mist = Color(0xFFE8E7DF)
    val Coral = Color(0xFFFF6B5B)
    val Adult = Color(0xFFE0457B)
}

private val Light = lightColorScheme(
    primary = Brand.Ink, onPrimary = Brand.Paper,
    primaryContainer = Brand.Volt, onPrimaryContainer = Brand.Ink,
    secondary = Brand.VoltDeep, onSecondary = Color.White,
    secondaryContainer = Brand.Mist, onSecondaryContainer = Brand.Ink,
    background = Brand.Paper, onBackground = Brand.Ink,
    surface = Brand.Paper, onSurface = Brand.Ink,
    surfaceVariant = Brand.Mist, onSurfaceVariant = Color(0xFF55564E),
    surfaceContainer = Color(0xFFEFEEE7), surfaceContainerHigh = Color(0xFFE9E8E0),
    error = Brand.Coral,
)

private val Dark = darkColorScheme(
    primary = Brand.Volt, onPrimary = Brand.Ink,
    primaryContainer = Brand.InkSoft, onPrimaryContainer = Brand.Volt,
    secondary = Brand.Volt, onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF33342E), onSecondaryContainer = Brand.Paper,
    background = Brand.Ink, onBackground = Brand.Paper,
    surface = Brand.Ink, onSurface = Brand.Paper,
    surfaceVariant = Brand.InkSoft, onSurfaceVariant = Color(0xFFB9B8B0),
    surfaceContainer = Color(0xFF1C1D19), surfaceContainerHigh = Color(0xFF24251F),
    error = Brand.Coral,
)

@Composable
fun StickerDockTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
