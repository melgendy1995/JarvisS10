package com.jarvis.orb

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun OrbView(
    state: OrbState,
    level: Float,
    accent: BrainAccent,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember { OrbRenderer() }
    val surface = remember {
        OrbGLSurfaceView(context, renderer).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }
    SideEffect {
        renderer.targetState = state
        renderer.audioLevel = level
        renderer.targetAccent = accent
    }
    DisposableEffect(lifecycleOwner, surface) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.resumeRendering()
                Lifecycle.Event.ON_PAUSE -> surface.pauseRendering()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            surface.resumeRendering()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface.pauseRendering()
        }
    }
    AndroidView(
        factory = { surface },
        modifier = modifier,
        onRelease = { it.pauseRendering() },
    )
}
