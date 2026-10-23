package com.example.toyotaobd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFC8102E)
private val RedLight = Color(0xFFFFB3B0)

/**
 * Couleurs dynamiques de la plateforme (Material You Android 12+), ou null si la plateforme
 * n'en propose pas : le thème de secours rouge Toyota est alors utilisé.
 */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?

@Composable
fun ToyotaObdTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = dynamicColorScheme(dark)
        ?: if (dark) darkColorScheme(primary = RedLight, secondary = RedLight)
        else lightColorScheme(primary = Red, secondary = Red)
    MaterialTheme(colorScheme = colors, content = content)
}
