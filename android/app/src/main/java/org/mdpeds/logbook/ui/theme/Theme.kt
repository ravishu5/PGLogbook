package org.mdpeds.logbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mdpeds.logbook.data.local.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF1F5F99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF0A2C4D),
    secondary = Color(0xFF3F6273),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9F0),
    onSecondaryContainer = Color(0xFF16313D),
    tertiary = Color(0xFF00696B),
    onTertiary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDFE3EA),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEBEEF3),
    surfaceContainerHigh = Color(0xFFE5E8ED),
    surfaceContainerHighest = Color(0xFFDFE2E7),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA0C9F2),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF14497A),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFB4CAD8),
    onSecondary = Color(0xFF1E3440),
    secondaryContainer = Color(0xFF354B57),
    onSecondaryContainer = Color(0xFFDCE9F0),
    tertiary = Color(0xFF7FD5D6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF262A2F),
    surfaceContainerHighest = Color(0xFF31353A),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB),
)

/** Meaningful status colours: grey not started, blue draft/in progress, orange pending, green approved, red returned. */
@Immutable
data class StatusColors(
    val notStarted: Color,
    val draft: Color,
    val pending: Color,
    val approved: Color,
    val returned: Color,
    val notStartedContainer: Color,
    val draftContainer: Color,
    val pendingContainer: Color,
    val approvedContainer: Color,
    val returnedContainer: Color,
)

private val LightStatus = StatusColors(
    notStarted = Color(0xFF5F6368), draft = Color(0xFF1A63C6), pending = Color(0xFFB35C00), approved = Color(0xFF1E7B34), returned = Color(0xFFC62828),
    notStartedContainer = Color(0xFFECEDEF), draftContainer = Color(0xFFE2ECFB), pendingContainer = Color(0xFFFFEEDB),
    approvedContainer = Color(0xFFE1F3E5), returnedContainer = Color(0xFFFDE4E4),
)

private val DarkStatus = StatusColors(
    notStarted = Color(0xFFBDC1C6), draft = Color(0xFF9CC2FF), pending = Color(0xFFFFB870), approved = Color(0xFF8FD6A0), returned = Color(0xFFFF9C9C),
    notStartedContainer = Color(0xFF2E3134), draftContainer = Color(0xFF1B2F4D), pendingContainer = Color(0xFF3D2A12),
    approvedContainer = Color(0xFF173322), returnedContainer = Color(0xFF431D1D),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun LogbookTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object AppTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
