package com.jarvis.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime

/** Nullable until a weather source is wired. Null renders the placeholder. */
data class WeatherState(val label: String)

@Composable
fun WeatherSlot(
    weather: WeatherState?,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val label = weather?.label?.takeIf { it.isNotBlank() } ?: "--"
    Text(
        text = label,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        style = TextStyle(textDirection = TextDirection.Content),
    )
}

@Composable
fun ShelfPanel(
    now: ZonedDateTime,
    weather: WeatherState?,
    you: String,
    reply: String,
    brain: String,
    night: Boolean,
    accessibilityHint: String?,
    onOpenSettings: () -> Unit,
    onOpenPersona: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = if (night) 0.72f else 1f
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val clockSize = with(density) { (maxHeight * 0.155f).toSp() }
        val dateSize = clockSize * 0.34f
        val weatherSize = clockSize * 0.42f
        val captionSize = clockSize * 0.58f
        val labelSize = clockSize * 0.22f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .semantics { contentDescription = "Groq settings" }
                    .pointerInput(onOpenSettings) {
                        detectTapGestures(onLongPress = { onOpenSettings() })
                    },
            ) {
                Text(
                    text = ShelfClock.formatTime(now),
                    color = Color.White.copy(alpha = ink),
                    fontSize = clockSize,
                    fontWeight = FontWeight.Bold,
                    lineHeight = clockSize,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    text = ShelfClock.formatDate(now),
                    modifier = Modifier.padding(top = 2.dp),
                    color = Color(0xFF9EE7FF).copy(alpha = ink),
                    fontSize = dateSize,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(Modifier.height(10.dp))
            WeatherSlot(
                weather = weather,
                color = Color(0xFF7FEFFF).copy(alpha = ink),
                fontSize = weatherSize,
            )
            Spacer(Modifier.weight(1f))
            if (you.isEmpty() && reply.isEmpty()) {
                Text(
                    text = "Tap or hold the orb",
                    color = Color(0xFF8FB4C8).copy(alpha = ink * 0.9f),
                    fontSize = captionSize * 0.72f,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                if (you.isNotEmpty()) {
                    CaptionBlock(
                        label = "Mahmoud",
                        text = you,
                        labelColor = Color(0xFF7FE7FF).copy(alpha = ink),
                        textColor = Color(0xFFE8FBFF).copy(alpha = ink),
                        labelSize = labelSize,
                        textSize = captionSize,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (reply.isNotEmpty()) {
                    CaptionBlock(
                        label = "Jarvis",
                        text = reply,
                        labelColor = Color(0xFFFFC56B).copy(alpha = ink),
                        textColor = Color(0xFFFFF6E8).copy(alpha = ink),
                        labelSize = labelSize,
                        textSize = captionSize,
                    )
                }
                if (brain.isNotEmpty()) {
                    Text(
                        text = brain,
                        modifier = Modifier.padding(top = 6.dp),
                        color = Color(0xFF9FB4C8).copy(alpha = ink * 0.8f),
                        fontSize = labelSize,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )
                }
            }
            Spacer(Modifier.weight(0.35f))
            if (!accessibilityHint.isNullOrEmpty()) {
                Text(
                    text = accessibilityHint,
                    color = Color(0xFF8AA0B5).copy(alpha = ink),
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = "ABOUT",
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "About Mahmoud" }
                    .clickable(onClick = onOpenPersona),
                color = Color(0xFF9EE7FF).copy(alpha = ink * 0.75f),
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun CaptionBlock(
    label: String,
    text: String,
    labelColor: Color,
    textColor: Color,
    labelSize: TextUnit,
    textSize: TextUnit,
) {
    val rtl = ShelfClock.prefersRtl(text)
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Start,
            )
            Text(
                text = text,
                color = textColor,
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                lineHeight = textSize * 1.05f,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(textDirection = TextDirection.Content),
            )
        }
    }
}
