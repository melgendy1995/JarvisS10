package com.jarvis.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Black = Color(0xFF000000)
private val Mist = Color(0xFF9FB4C8)
private val Phone = Color(0xFF3FD8FF)

private val JarvisColors = darkColorScheme(
    primary = Phone,
    onPrimary = Black,
    background = Black,
    onBackground = Mist,
    surface = Black,
    onSurface = Mist,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColors,
        content = content,
    )
}
