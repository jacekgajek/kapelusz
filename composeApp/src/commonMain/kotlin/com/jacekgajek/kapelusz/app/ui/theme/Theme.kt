package com.jacekgajek.kapelusz.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF6B4EA2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = Color(0xFF00696E),
    secondaryContainer = Color(0xFFB3F0F3),
    tertiary = Color(0xFF8B5000),
    tertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD3BBFF),
    onPrimary = Color(0xFF3B1A71),
    primaryContainer = Color(0xFF523589),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFF4FD8DE),
    secondaryContainer = Color(0xFF004F53),
    tertiary = Color(0xFFFFB871),
    tertiaryContainer = Color(0xFF6A3B00),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

/** Colors for money semantics, readable on both schemes. */
object MoneyColors {
    val positive @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7DDC8A) else Color(0xFF1B6D2A)
    val negative @Composable get() = MaterialTheme.colorScheme.error
}

@Composable
fun KapeluszTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
