package ru.blockscan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.blockscan.app.domain.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFF0), onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF3B5BA9), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4FA), onSecondaryContainer = Color(0xFF0B1F4D),
    background = Color(0xFFF6FAFB), onBackground = Color(0xFF0F1A1D),
    surface = Color(0xFFF6FAFB), onSurface = Color(0xFF0F1A1D),
    surfaceVariant = Color(0xFFDDE7EA), onSurfaceVariant = Color(0xFF3B494D),
    outline = Color(0xFF6B7B80), error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0B3B3F), onPrimaryContainer = Color(0xFFB8F2EC),
    secondary = Color(0xFF9DB4F0), onSecondary = Color(0xFF0B1F4D),
    secondaryContainer = Color(0xFF223463), onSecondaryContainer = Color(0xFFDCE4FA),
    background = Color(0xFF0B1418), onBackground = Color(0xFFE3EDEF),
    surface = Color(0xFF0B1418), onSurface = Color(0xFFE3EDEF),
    surfaceVariant = Color(0xFF1B2A30), onSurfaceVariant = Color(0xFFB4C4C9),
    outline = Color(0xFF7F9096), error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
}
