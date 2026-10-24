package com.jarvis.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

private val Groq = Color(0xFFFF9E40)
private val Mist = Color(0xFF9FB4C8)
private val Faint = Color(0xFF73808C)

@Composable
fun GroqSettings(
    proxyReady: Boolean,
    bearerSaved: Boolean,
    keystoreReady: Boolean,
    cameraPasswordSaved: Boolean,
    cameraKeystoreReady: Boolean,
    cameraPort: Int,
    cameraLan: String,
    cameraTailscale: String,
    onSave: (String) -> Unit,
    onSaveCamera: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var cameraPassword by remember { mutableStateOf("") }
    var portText by remember(cameraPort) { mutableStateOf(cameraPort.toString()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Text(
            text = "CLOSE",
            color = Faint,
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Close" }
                .clickable(onClick = onClose),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
        Text(
            text = "GROQ",
            color = Groq.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (proxyReady) {
                "Proxy URL is set."
            } else {
                "Set GROQ_PROXY_URL in local.properties, then rebuild."
            },
            color = Mist,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                !keystoreReady -> "The keystore is unavailable, so the bearer cannot be saved."
                bearerSaved -> "A bearer is saved on this phone."
                else -> "Paste the worker bearer once. It stays in the keystore."
            },
            color = Faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text("Worker bearer") },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "SAVE",
            color = Groq.copy(alpha = 0.9f),
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.clickable { onSave(token) },
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "CAMERA",
            color = Groq.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = when {
                !cameraKeystoreReady -> "The keystore is unavailable, so the camera password cannot be saved."
                cameraPasswordSaved -> "A camera password is saved on this phone."
                else -> "Set a camera password. The stream stays off until one is saved."
            },
            color = Faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = cameraLan,
            color = Mist,
            fontSize = 15.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = cameraTailscale,
            color = Mist,
            fontSize = 15.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = cameraPassword,
            onValueChange = { cameraPassword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text("Camera password") },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("Port") },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "SAVE CAMERA",
            color = Groq.copy(alpha = 0.9f),
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.clickable { onSaveCamera(cameraPassword, portText) },
        )
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color(0xFFCFE6F5),
    unfocusedTextColor = Color(0xFFCFE6F5),
    focusedBorderColor = Groq,
    unfocusedBorderColor = Faint,
    cursorColor = Groq,
    focusedLabelColor = Groq,
    unfocusedLabelColor = Faint,
)
