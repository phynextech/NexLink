package com.phynex.NexLink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val primary: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val onPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary
val primaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
val onPrimaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
val inversePrimary: Color
    @Composable get() = MaterialTheme.colorScheme.inversePrimary

val secondary: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
val onSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSecondary
val secondaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer
val onSecondaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer

val tertiary: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary
val onTertiary: Color
    @Composable get() = MaterialTheme.colorScheme.onTertiary
val tertiaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
val onTertiaryContainer: Color
    @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer

val error: Color
    @Composable get() = MaterialTheme.colorScheme.error
val onError: Color
    @Composable get() = MaterialTheme.colorScheme.onError
val errorContainer: Color
    @Composable get() = MaterialTheme.colorScheme.errorContainer
val onErrorContainer: Color
    @Composable get() = MaterialTheme.colorScheme.onErrorContainer

val background: Color
    @Composable get() = MaterialTheme.colorScheme.background
val onBackground: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

val surface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
val onSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val surfaceVariant: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val onSurfaceVariant: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val inverseSurface: Color
    @Composable get() = MaterialTheme.colorScheme.inverseSurface
val inverseOnSurface: Color
    @Composable get() = MaterialTheme.colorScheme.inverseOnSurface

val outline: Color
    @Composable get() = MaterialTheme.colorScheme.outline
val outlineVariant: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant

val surfaceTint: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceTint

// Custom specific colors for the app
val GreenLight = Color(0xFF4CAF50)
val GreenConnected = Color(0xFF238636)
val RedDisconnected = Color(0xFFDA3633)
val OrangeWarning = Color(0xFFD29922)
val GradientStart = Color(0xFF091421)
val GradientEnd = Color(0xFF050f1c)

val GlassSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
val GlassBorder: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
