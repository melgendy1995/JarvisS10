package com.jarvis.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.JarvisEvent
import com.jarvis.app.JarvisViewModel
import com.jarvis.app.R
import com.jarvis.app.debug.OrbDebug
import com.jarvis.app.system.CameraRuntime
import com.jarvis.app.system.JarvisAccessibilityService
import com.jarvis.orb.OrbView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun JarvisScreen(viewModel: JarvisViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val forced by OrbDebug.forced.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is JarvisEvent.RequestPermission -> permissionLauncher.launch(event.permission)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onScreenVisible()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var now by remember { mutableStateOf(ShelfClock.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = ShelfClock.now()
            delay(1_000)
        }
    }
    val night = ShelfClock.isNight(now)
    ShelfBrightness(night)

    var drift by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(Unit) {
        var x = 0
        var y = 0
        var step = 0
        val dx = intArrayOf(3, -2, 2, -3, 3, -2)
        val dy = intArrayOf(-2, 3, -3, 2, 2, -3)
        while (isActive) {
            delay(60_000)
            x = (x + dx[step % dx.size]).coerceIn(-8, 8)
            y = (y + dy[step % dy.size]).coerceIn(-6, 6)
            step++
            drift = IntOffset(x, y)
        }
    }

    val overlay = ui.personaOpen || ui.groqSettings
    BackHandler(enabled = overlay) {
        if (ui.personaOpen) viewModel.closePersona() else viewModel.closeGroqSettings()
    }
    val streaming by CameraRuntime.streaming.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!overlay) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .offset { drift }
                    .padding(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    TalkOrb(
                        phase = forced ?: ui.orb,
                        night = night,
                        demoMotion = forced != null,
                        holding = ui.holding,
                        onHoldStart = viewModel::onHoldStart,
                        onHoldEnd = viewModel::onHoldEnd,
                        modifier = Modifier
                            .fillMaxHeight(0.72f)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true),
                    )
                }
                ShelfPanel(
                    now = now,
                    weather = ui.weatherLabel?.let { WeatherState(it) },
                    lan = ui.shelfLan,
                    you = ui.you,
                    reply = ui.reply,
                    sources = ui.sources,
                    searchHtml = ui.searchHtml,
                    brain = ui.brain,
                    night = night,
                    accessibilityHint = accessibilityHint(),
                    onOpenSettings = viewModel::openGroqSettings,
                    onOpenPersona = viewModel::openPersona,
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight(),
                )
            }
        }
        if (ui.personaOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PersonaEditor(
                    name = ui.personaName,
                    initialPersona = ui.personaText,
                    onSave = viewModel::savePersona,
                    onClose = viewModel::closePersona,
                )
            }
        } else if (ui.groqSettings) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                GroqSettings(
                    proxyReady = ui.groqProxyReady,
                    bearerSaved = ui.groqBearerSaved,
                    keystoreReady = ui.groqKeystoreReady,
                    cameraPasswordSaved = ui.cameraPasswordSaved,
                    cameraKeystoreReady = ui.cameraKeystoreReady,
                    cameraPort = ui.cameraPort,
                    cameraLan = ui.cameraLan,
                    cameraTailscale = ui.cameraTailscale,
                    voiceLanguage = ui.voiceLanguage,
                    onSave = viewModel::saveGroqBearer,
                    onSaveCamera = viewModel::saveCameraAccess,
                    onSetVoiceLanguage = viewModel::setVoiceLanguage,
                    onClose = viewModel::closeGroqSettings,
                )
            }
        }
        if (streaming) {
            StreamingMark(Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun StreamingMark(modifier: Modifier = Modifier) {
    val live = Color(0xFFFF2A2A)
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(live, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "LIVE",
            color = live,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun TalkOrb(
    phase: com.jarvis.orb.OrbState,
    night: Boolean,
    demoMotion: Boolean,
    holding: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val talk = stringResource(R.string.hold_to_talk)
    val latched = remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) latched.value = false
    }
    OrbView(
        state = phase,
        night = night,
        demoMotion = demoMotion,
        modifier = modifier
            .semantics { contentDescription = talk }
            .pointerInput(onHoldStart, onHoldEnd) {
                val flag = latched
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (flag.value) {
                        flag.value = false
                        onHoldEnd()
                        waitForUpOrCancellation()
                    } else {
                        val started = android.os.SystemClock.uptimeMillis()
                        onHoldStart()
                        val up = waitForUpOrCancellation()
                        val elapsed = android.os.SystemClock.uptimeMillis() - started
                        if (up != null && elapsed < TAP_LATCH_MS) {
                            flag.value = true
                        } else {
                            flag.value = false
                            onHoldEnd()
                        }
                    }
                }
            },
    )
}

@Composable
private fun accessibilityHint(): String? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(JarvisAccessibilityService.isEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = JarvisAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (enabled) return null
    return stringResource(R.string.accessibility_onboarding)
}

@Composable
private fun ShelfBrightness(night: Boolean) {
    val context = LocalContext.current
    SideEffect {
        val activity = context.findActivity() ?: return@SideEffect
        val window = activity.window
        val target = if (night) {
            NIGHT_BRIGHTNESS
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val attrs = window.attributes
        if (attrs.screenBrightness != target) {
            attrs.screenBrightness = target
            window.attributes = attrs
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val TAP_LATCH_MS = 280L
private const val NIGHT_BRIGHTNESS = 0.14f
