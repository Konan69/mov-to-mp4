package dev.konan.mov2mp4.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Brand colours that Material's scheme has no slot for. */
@Immutable
data class Brand(
    val mint: Color,
    val mintContainer: Color,
    val onMintContainer: Color,
    val amberContainer: Color,
    val onAmberContainer: Color,
    val iconTop: Color,
    val iconMid: Color,
    val iconBottom: Color,
    val hairline: Color,
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }

private val IconTop = Color(0xFF8A6BFF)
private val IconMid = Color(0xFF5A3FEA)
private val IconBottom = Color(0xFF2A1BA8)

private val DarkBrand = Brand(
    mint = Color(0xFF4BE5AA),
    mintContainer = Color(0xFF0F3A2C),
    onMintContainer = Color(0xFF8DF5CE),
    amberContainer = Color(0xFF3D2E0C),
    onAmberContainer = Color(0xFFFFD27A),
    iconTop = IconTop,
    iconMid = IconMid,
    iconBottom = IconBottom,
    hairline = Color(0xFF262A3C),
)

private val LightBrand = Brand(
    mint = Color(0xFF0C9A6B),
    mintContainer = Color(0xFFDAF5EA),
    onMintContainer = Color(0xFF06583C),
    amberContainer = Color(0xFFFFEFC9),
    onAmberContainer = Color(0xFF6E4A00),
    iconTop = IconTop,
    iconMid = IconMid,
    iconBottom = IconBottom,
    hairline = Color(0xFFE2E3EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9285FF),
    onPrimary = Color(0xFF120A4D),
    primaryContainer = Color(0xFF2B2466),
    onPrimaryContainer = Color(0xFFE0DCFF),
    secondary = Color(0xFF4BE5AA),
    onSecondary = Color(0xFF00291B),
    secondaryContainer = Color(0xFF23263A),
    onSecondaryContainer = Color(0xFFE4E5F2),
    background = Color(0xFF0B0C12),
    onBackground = Color(0xFFF1F2F8),
    surface = Color(0xFF0B0C12),
    onSurface = Color(0xFFF1F2F8),
    surfaceVariant = Color(0xFF1D2030),
    onSurfaceVariant = Color(0xFFA2A7BE),
    surfaceContainerLowest = Color(0xFF08090E),
    surfaceContainerLow = Color(0xFF12141D),
    surfaceContainer = Color(0xFF161823),
    surfaceContainerHigh = Color(0xFF1D2030),
    surfaceContainerHighest = Color(0xFF272B3F),
    outline = Color(0xFF4A4F68),
    outlineVariant = Color(0xFF262A3C),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF4A0A0A),
    errorContainer = Color(0xFF3B1719),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5546F2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E2FF),
    onPrimaryContainer = Color(0xFF1B1270),
    secondary = Color(0xFF0C9A6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9EAF4),
    onSecondaryContainer = Color(0xFF1A1C28),
    background = Color(0xFFF5F5FA),
    onBackground = Color(0xFF12131A),
    surface = Color(0xFFF5F5FA),
    onSurface = Color(0xFF12131A),
    surfaceVariant = Color(0xFFE9EAF4),
    onSurfaceVariant = Color(0xFF5B6077),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEEFF7),
    surfaceContainerHighest = Color(0xFFE4E5F0),
    outline = Color(0xFF9EA2B8),
    outlineVariant = Color(0xFFE2E3EE),
    error = Color(0xFFD23B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF6B1414),
    scrim = Color(0xFF000000),
)

private val AppType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        headlineMedium = TextStyle(
            fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em
        ),
        headlineSmall = TextStyle(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em
        ),
        titleLarge = TextStyle(
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em
        ),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em
        ),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppType,
            shapes = AppShapes,
            content = content,
        )
    }
}
