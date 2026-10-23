package com.jarvis.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.data.MemoryLimits

private val Phone = Color(0xFF3FD8FF)
private val Mist = Color(0xFFCFE6F5)
private val Faint = Color(0xFF73808C)

@Composable
fun PersonaEditor(
    name: String,
    initialPersona: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable(initialPersona) { mutableStateOf(initialPersona) }
    val title = name.trim().ifEmpty { "Mahmoud" }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
    ) {
        Text(
            text = "ABOUT ${title.uppercase()}",
            color = Phone.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "This stays on the phone and is added to cloud replies.",
            color = Faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                if (next.length <= MemoryLimits.PERSONA_CHARS) text = next
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp),
            minLines = 6,
            label = { Text("Persona") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Mist,
                unfocusedTextColor = Mist,
                focusedBorderColor = Phone,
                unfocusedBorderColor = Faint,
                cursorColor = Phone,
                focusedLabelColor = Phone,
                unfocusedLabelColor = Faint,
                focusedContainerColor = Color.Black,
                unfocusedContainerColor = Color.Black,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${text.length} / ${MemoryLimits.PERSONA_CHARS}",
            color = Faint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "SAVE",
            color = Phone.copy(alpha = 0.9f),
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.clickable { onSave(text) },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "CLOSE",
            color = Faint,
            fontSize = 14.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}
