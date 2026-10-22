package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = ElectricTeal,
  onPrimary = DeepNavy,
  primaryContainer = NavyCardElevated,
  onPrimaryContainer = CyanGlow,
  secondary = TealAccent,
  onSecondary = DeepNavy,
  secondaryContainer = NavyCard,
  onSecondaryContainer = SkyBlue,
  tertiary = SkyBlue,
  background = DeepNavy,
  onBackground = Color.White,
  surface = NavySurface,
  onSurface = Color.White,
  surfaceVariant = NavyCard,
  onSurfaceVariant = Color(0xFFB0BDD6),
  outline = NavyBorder,
  outlineVariant = Color(0xFF1E2B52),
  error = StatusRed,
  onError = Color.White
)

private val LightColorScheme = lightColorScheme(
  primary = LightTeal,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE0F2F1),
  onPrimaryContainer = Color(0xFF004D40),
  secondary = DeepNavy,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFD6E4FF),
  onSecondaryContainer = DeepNavy,
  tertiary = SkyBlue,
  background = LightBackground,
  onBackground = DarkNavyText,
  surface = LightSurface,
  onSurface = DarkNavyText,
  surfaceVariant = LightCard,
  onSurfaceVariant = Color(0xFF4A5568),
  outline = Color(0xFFCBD5E1),
  outlineVariant = Color(0xFFE2E8F0),
  error = StatusRed,
  onError = Color.White
)

@Composable
fun OGTunnelTheme(
  darkTheme: Boolean = true, // Default to dark navy theme for cyberpunk VPN feel
  dynamicColor: Boolean = false, // Keep signature navy/teal branding by default
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

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

// Backward compatible alias
@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit
) = OGTunnelTheme(darkTheme, dynamicColor, content)
