package com.jarvis.orb

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout

@Composable
fun OrbView(
    state: OrbState,
    night: Boolean,
    demoMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {},
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .ringOrb(state = state, night = night, demoMotion = demoMotion),
    ) { _, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        layout(width, height) {}
    }
}
