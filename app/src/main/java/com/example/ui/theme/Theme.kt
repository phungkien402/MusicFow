package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppTheme = staticCompositionLocalOf { AppTheme.CYBERPUNK }

@Composable
fun MyApplicationTheme(
  theme: AppTheme = AppTheme.CYBERPUNK,
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = darkColorScheme(
    primary = theme.primary,
    onPrimary = Color(0xFF031A24),
    primaryContainer = theme.primary.copy(alpha = 0.2f),
    onPrimaryContainer = theme.primary,
    secondary = theme.secondary,
    onSecondary = Color.White,
    secondaryContainer = theme.secondary.copy(alpha = 0.2f),
    onSecondaryContainer = theme.secondary,
    tertiary = theme.accent,
    onTertiary = Color.White,
    tertiaryContainer = theme.accent.copy(alpha = 0.2f),
    onTertiaryContainer = theme.accent,
    background = theme.background,
    onBackground = TextPrimary,
    surface = theme.surface,
    onSurface = TextPrimary,
    surfaceVariant = theme.surfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = theme.cardBorder
  )

  CompositionLocalProvider(LocalAppTheme provides theme) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}

