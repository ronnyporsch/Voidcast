package de.ronnyporsch.voidcast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AccentColor = Color(0xFFFF00FF)
val SpotifyBlack = Color(0xFF121212)
val SpotifyDarkGray = Color(0xFF181818)
val SpotifySurface = Color(0xFF282828)
val SpotifyLightGray = Color(0xFFB3B3B3)
val SpotifyWhite = Color(0xFFFFFFFF)
/** Placeholder of search and filter fields; set explicitly, as the text styles hardcode white. */
val SearchPlaceholderGray = Color(0xFF757575)

/** Background of selected songs and albums; tinted, so it shows on every background, the sidebar's included. */
val SelectionColor = AccentColor.copy(alpha = 0.22f)

private val DarkColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = SpotifyBlack,
    secondary = AccentColor,
    onSecondary = SpotifyBlack,
    background = SpotifyBlack,
    onBackground = SpotifyWhite,
    surface = SpotifyDarkGray,
    onSurface = SpotifyWhite,
    surfaceVariant = SpotifySurface,
    onSurfaceVariant = SpotifyLightGray,
    tertiary = SpotifyLightGray,
    // Snackbar colors
    inverseSurface = Color(0xFF3A3A3A),
    inverseOnSurface = SpotifyWhite,
    inversePrimary = AccentColor,
    error = Color(0xFFCF6679),
    onError = SpotifyBlack,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        color = SpotifyWhite,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = SpotifyWhite,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = SpotifyWhite,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = SpotifyWhite,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = SpotifyWhite,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = SpotifyWhite,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SpotifyLightGray,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = SpotifyLightGray,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SpotifyWhite,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = SpotifyLightGray,
    ),
)

@Composable
fun VoidcastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
