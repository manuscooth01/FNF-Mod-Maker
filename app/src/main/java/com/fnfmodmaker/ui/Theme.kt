package com.fnfmodmaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FnfPurple = Color(0xFFF38BCD)
val FnfBlue = Color(0xFF68DDEB)
val FnfGreen = Color(0xFF12FA05)
val FnfRed = Color(0xFFF9393F)
val Ink = Color(0xFF0B0B0F)
val SurfaceOne = Color(0xFF16161F)
val SurfaceTwo = Color(0xFF1F1F2B)
val Paper = Color(0xFFF5F5FA)
val PaperDim = Color(0xFFC8C2D4)

private val Scheme = darkColorScheme(
    primary = FnfPurple,
    onPrimary = Color(0xFF260B20),
    primaryContainer = Color(0xFF3A1730),
    onPrimaryContainer = Color(0xFFFFD9F2),
    secondary = FnfBlue,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF003538),
    onSecondaryContainer = Color(0xFFB8FEFF),
    tertiary = FnfGreen,
    onTertiary = Color.Black,
    background = Ink,
    onBackground = Paper,
    surface = SurfaceOne,
    onSurface = Paper,
    surfaceVariant = SurfaceTwo,
    onSurfaceVariant = PaperDim,
    error = FnfRed,
    onError = Color.White,
    errorContainer = Color(0xFF3A1114),
    onErrorContainer = Color(0xFFFFD9DA),
    outline = Color(0xFF64596F)
)

private val defaultType = Typography()
private val AppType = defaultType.copy(
    headlineMedium = defaultType.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = defaultType.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleMedium = defaultType.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    bodySmall = defaultType.bodySmall.copy(lineHeight = 20.sp),
    labelSmall = defaultType.labelSmall.copy(lineHeight = 17.sp)
)
private val AppShapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(26.dp))

@Composable
fun FNFTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppType, shapes = AppShapes, content = content)
}
