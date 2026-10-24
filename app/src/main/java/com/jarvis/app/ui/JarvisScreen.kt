package com.jarvis.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.JarvisEvent
import com.jarvis.app.JarvisViewModel
import com.jarvis.app.R
import com.jarvis.app.system.JarvisAccessibilityService
import com.jarvis.orb.BrainAccent
import com.jarvis.orb.OrbState
import com.jarvis.orb.OrbView
import kotlinx.coroutines.delay

@Composable
fun JarvisScreen(viewModel: JarvisViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
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

    Box(Modifier.fillMaxSize()) {
        OrbView(
            state = ui.orb,
            level = ui.level,
            accent = ui.accent,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            StateLabel(
                phase = ui.orb,
                accent = ui.accent,
                onOpenSettings = viewModel::openGroqSettings,
                onOpenPersona = viewModel::openPersona,
            )
            Spacer(Modifier.height(12.dp))
            Captions(
                you = ui.you,
                reply = ui.reply,
                phase = ui.orb,
                brain = ui.brain,
                accent = ui.accent,
            )
            AccessibilityHint()
            HoldToTalk(
                holding = ui.holding,
                onHoldStart = viewModel::onHoldStart,
                onHoldEnd = viewModel::onHoldEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
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
                    onSave = viewModel::saveGroqBearer,
                    onClose = viewModel::closeGroqSettings,
                )
            }
        }
    }
}

@Composable
private fun StateLabel(
    phase: OrbState,
    accent: BrainAccent,
    onOpenSettings: () -> Unit,
    onOpenPersona: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "drift")
    val driftX by transition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftX",
    )
    val driftY by transition.animateFloat(
        initialValue = 4f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "driftY",
    )
    val color = Color(accent.red, accent.green, accent.blue)
    Column(
        modifier = Modifier.offset(x = driftX.dp, y = driftY.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (phase == OrbState.ERROR) "OFFLINE" else phase.name,
            modifier = Modifier
                .semantics { contentDescription = "Groq settings" }
                .pointerInput(onOpenSettings) {
                    detectTapGestures(onLongPress = { onOpenSettings() })
                },
            color = color.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
        )
        Text(
            text = "ABOUT",
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { contentDescription = "About Mahmoud" }
                .pointerInput(onOpenPersona) {
                    detectTapGestures(onTap = { onOpenPersona() })
                },
            color = color.copy(alpha = 0.35f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun Captions(
    you: String,
    reply: String,
    phase: OrbState,
    brain: String,
    accent: BrainAccent,
) {
    var alpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(you, reply, phase, brain) {
        if (you.isEmpty() && reply.isEmpty() && brain.isEmpty()) {
            alpha = 0f
            return@LaunchedEffect
        }
        alpha = 0.88f
        if (phase == OrbState.IDLE || phase == OrbState.ERROR) {
            delay(8_000)
            alpha = 0f
        }
    }
    val animated by animateFloatAsState(alpha, animationSpec = tween(600), label = "caption")
    val accentColor = Color(accent.red, accent.green, accent.blue)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .graphicsLayer { this.alpha = animated },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (brain.isNotEmpty()) {
            Text(
                text = brain,
                color = accentColor.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (you.isNotEmpty()) {
            Text(
                text = you,
                color = Color(0xFF6F8598),
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            )
        }
        if (reply.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reply,
                color = Color(0xFFCFE6F5),
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AccessibilityHint() {
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
    if (enabled) return
    Text(
        text = stringResource(R.string.accessibility_onboarding),
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        color = Color(0xFF8AA0B5),
        fontSize = 13.sp,
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HoldToTalk(
    holding: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF3FD8FF)
    Box(
        modifier = modifier
            .semantics { contentDescription = "Hold to talk" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onHoldStart()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        onHoldEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(148.dp)) {
            val stroke = 2.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            drawCircle(
                color = accent.copy(alpha = if (holding) 0.9f else 0.35f),
                radius = radius,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = stroke),
            )
            drawCircle(
                color = accent.copy(alpha = if (holding) 0.16f else 0.04f),
                radius = radius * 0.72f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        Text(
            text = if (holding) "LISTENING" else "HOLD TO TALK",
            color = accent.copy(alpha = if (holding) 0.95f else 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
        )
    }
}
