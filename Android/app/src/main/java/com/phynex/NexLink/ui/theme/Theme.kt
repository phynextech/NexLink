package com.phynex.NexLink.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkMonochromeScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    outline = Color(0xFF666666)
)

private val LightMonochromeScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color.Black,
    outline = Color(0xFFAAAAAA)
)

@Composable
fun LinkBridgeTheme(
    themeMode: String = "System",
    primaryColorName: String = "Monochrome",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    }
    
    val primaryColorVal = when(primaryColorName) {
        "Green" -> Color(0xFF4CAF50)
        "Pink" -> Color(0xFFE91E63)
        "Lavender" -> Color(0xFF9C27B0)
        "Orange" -> Color(0xFFFF9800)
        "Blue" -> Color(0xFF2196F3)
        "Red" -> Color(0xFFF44336)
        else -> if (darkTheme) Color.White else Color.Black
    }

    val colorScheme = if (darkTheme) {
        DarkMonochromeScheme.copy(primary = primaryColorVal)
    } else {
        LightMonochromeScheme.copy(primary = primaryColorVal)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

fun Modifier.glassCard(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)
): Modifier = this
    .clip(shape)
    .background(Color(0x1A888888))
    .border(1.dp, Color(0x33888888), shape)

