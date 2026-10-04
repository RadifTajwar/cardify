package com.cardify

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cardify's own indigo palette rather than wallpaper colors, so the app looks the same on every phone.
private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF), onPrimaryContainer = Color(0xFF1A1265),
    secondary = Color(0xFF5D5B7D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E1F7), onSecondaryContainer = Color(0xFF1A1933),
    tertiary = Color(0xFF00897B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCF1EA), onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7F7FC), onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFF7F7FC), onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE5E3EF), onSurfaceVariant = Color(0xFF5E5C6C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F0F8),
    surfaceContainer = Color(0xFFECEBF4), surfaceContainerHigh = Color(0xFFE6E5EF),
    surfaceContainerHighest = Color(0xFFE0DFEA),
    outline = Color(0xFF7A7888), outlineVariant = Color(0xFFCBC8D6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC2C0FF), onPrimary = Color(0xFF241A8F),
    primaryContainer = Color(0xFF3B33C8), onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFFC7C4E6), onSecondary = Color(0xFF2F2E4A),
    secondaryContainer = Color(0xFF454462), onSecondaryContainer = Color(0xFFE3E1F7),
    tertiary = Color(0xFF6FD9C8), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048), onTertiaryContainer = Color(0xFFCCF1EA),
    background = Color(0xFF111117), onBackground = Color(0xFFE5E3EC),
    surface = Color(0xFF111117), onSurface = Color(0xFFE5E3EC),
    surfaceVariant = Color(0xFF46454F), onSurfaceVariant = Color(0xFFC8C5D2),
    surfaceContainerLowest = Color(0xFF0C0C11), surfaceContainerLow = Color(0xFF19191F),
    surfaceContainer = Color(0xFF1D1D24), surfaceContainerHigh = Color(0xFF28272F),
    surfaceContainerHighest = Color(0xFF33323A),
    outline = Color(0xFF928F9D), outlineVariant = Color(0xFF46454F),
)

/** The brand gradient: the login screen's backdrop. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF6D28D9), Color(0xFFC026D3)))

// Plus Jakarta Sans, one variable font file for every weight.
private fun jakarta(weight: Int) =
    Font(R.font.plus_jakarta_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

private fun TextStyle.jakarta(weight: FontWeight, tracking: Float = 0f) =
    copy(fontFamily = Jakarta, fontWeight = weight, letterSpacing = if (tracking != 0f) tracking.sp else letterSpacing)

private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.jakarta(FontWeight.ExtraBold, -1f),
        displayMedium = displayMedium.jakarta(FontWeight.ExtraBold, -0.8f),
        displaySmall = displaySmall.jakarta(FontWeight.ExtraBold, -0.6f),
        headlineLarge = headlineLarge.jakarta(FontWeight.Bold, -0.5f),
        headlineMedium = headlineMedium.jakarta(FontWeight.Bold, -0.4f),
        headlineSmall = headlineSmall.jakarta(FontWeight.Bold, -0.2f),
        titleLarge = titleLarge.jakarta(FontWeight.Bold),
        titleMedium = titleMedium.jakarta(FontWeight.SemiBold),
        titleSmall = titleSmall.jakarta(FontWeight.SemiBold),
        bodyLarge = bodyLarge.jakarta(FontWeight.Normal),
        bodyMedium = bodyMedium.jakarta(FontWeight.Normal),
        bodySmall = bodySmall.jakarta(FontWeight.Medium),
        labelLarge = labelLarge.jakarta(FontWeight.SemiBold),
        labelMedium = labelMedium.jakarta(FontWeight.SemiBold),
        labelSmall = labelSmall.jakarta(FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CardifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
