package com.fnfmodmaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FnfPurple = Color(0xFFC24B99)
val FnfBlue = Color(0xFF00FFFF)
val FnfGreen = Color(0xFF12FA05)
val FnfRed = Color(0xFFF9393F)
val Ink = Color(0xFF0B0B0F)
val SurfaceOne = Color(0xFF16161F)
val SurfaceTwo = Color(0xFF1F1F2B)
val Paper = Color(0xFFF5F5FA)
val PaperDim = Color(0xFFA0A0B0)

private val Scheme = darkColorScheme(
    primary = FnfPurple,
    onPrimary = Color.White,
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
    outline = Color(0xFF3A3A4A)
)

@Composable
fun FNFTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
