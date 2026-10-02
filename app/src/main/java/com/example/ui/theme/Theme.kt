package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SoundFlowColorScheme = darkColorScheme(
  primary = NeonCyan,
  onPrimary = Color(0xFF031A24),
  primaryContainer = Color(0xFF0C3547),
  onPrimaryContainer = Color(0xFF88F2FF),
  secondary = NeonPurple,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFF3B1E63),
  onSecondaryContainer = Color(0xFFE9D5FF),
  tertiary = NeonPink,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFF5A102A),
  onTertiaryContainer = Color(0xFFFFD9E2),
  background = DarkBackground,
  onBackground = TextPrimary,
  surface = DarkSurface,
  onSurface = TextPrimary,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = TextSecondary,
  outline = DarkCardBorder
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = SoundFlowColorScheme,
    typography = Typography,
    content = content
  )
}

