package com.example.safe_kids.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    secondary = Color(0xFFC5CAE9),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF0F172A), // Deeper dark blue/gray
    surface = Color(0xFF1E293B),    // Slate dark
    onPrimary = Color(0xFF1A1C1E),
    onSecondary = Color(0xFF1A1C1E),
    onTertiary = Color(0xFF1A1C1E),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3F51B5),
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFF009688),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B)
)

@Composable
fun SAFE_KIDSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textSize: Float = 1.0f,
    displaySize: Float = 1.0f,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Apply scaling to density
    val fontScale = LocalDensity.current.fontScale * textSize
    val densityScale = LocalDensity.current.density * displaySize
    
    val customDensity = Density(
        density = densityScale,
        fontScale = fontScale
    )

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
