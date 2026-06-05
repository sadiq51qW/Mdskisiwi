package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFA816B),
    onPrimary = Color(0xFF5C1D11),
    primaryContainer = Color(0xFF7E3021),
    onPrimaryContainer = Color(0xFFFDDED9),
    secondary = Color(0xFFD17464),
    onSecondary = Color(0xFF3B0B04),
    secondaryContainer = Color(0xFF551E14),
    onSecondaryContainer = Color(0xFFFBE0DA),
    tertiary = Color(0xFFC7B1AC),
    onTertiary = Color(0xFF2B211F),
    background = Color(0xFF211A18),
    onBackground = Color(0xFFEBE0DD),
    surface = Color(0xFF2B2321),
    onSurface = Color(0xFFEBE0DD),
    surfaceVariant = Color(0xFF4C3E3B),
    onSurfaceVariant = Color(0xFFD4C2BF),
    outline = Color(0xFF9E8E8B),
    outlineVariant = Color(0xFF4C3E3B)
)

private val LightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = RosySand,
    onPrimaryContainer = EarthDark,
    secondary = TerracottaPrimaryVariant,
    onSecondary = Color.White,
    secondaryContainer = OffRoseSand,
    onSecondaryContainer = EarthDark,
    tertiary = MutedEarth,
    onTertiary = Color.White,
    background = CozyBackground,
    onBackground = EarthDark,
    surface = SandSurface,
    onSurface = EarthDark,
    surfaceVariant = OffRoseSand,
    onSurfaceVariant = MutedEarth,
    outline = OffRoseSand,
    outlineVariant = RosySand
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep raw Immersive UI theme design active by defaulting dynamicColor to false
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
