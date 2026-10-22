package com.medreminder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green = Color(0xFF1B7F3B)
val Paper = Color(0xFFFBFBF8)
val Ink = Color(0xFF1F1F1F)
val Muted = Color(0xFF5F5F5F)
val Grey = Color(0xFFE6E6E6)
val Red = Color(0xFFB3261E)
val Amber = Color(0xFF9A6B00)
val Blue = Color(0xFF34557A)

// Dark text on a plain light background in both modes; no dark theme for the elder.
private val Colors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Blue,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Grey,
    onSurfaceVariant = Muted,
    error = Red,
)

/** Body text at least 22sp; sp follows the phone's font-size setting. */
private val ElderTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodySmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FamilyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

@Composable
fun ElderTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Colors, typography = ElderTypography, content = content)
