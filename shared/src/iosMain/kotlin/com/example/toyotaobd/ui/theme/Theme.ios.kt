package com.example.toyotaobd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS n'expose pas de palette système compatible Material : thème rouge Toyota. */
@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null
