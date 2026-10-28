package com.example.smartclassifier.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AppScreen(val title: String, val subtitle: String, val icon: String) {
    ANALYZE("Face Analyze & Swap", "Mood, pose and face swap", "🧠"),
    STICKER("Sticker", "Long-press an object to lift it", "✂"),
    AR_DOODLE("AR Doodle", "Draw lines or string lights in 3D, record video", "✏"),
    AIR_WRITE("Air-Write", "Write in the air, get 3D text", "🔤"),
    AR_EMOJI("AR Emoji", "Avatar that copies your face", "😀"),
    HAND_AIR_WRITE("Hand Air-Write", "Write in the air with hand gestures → text", "🖐"),
    GESTURE_SELFIE("Gesture Selfie", "Show ✌️ to take a photo, 🖐 to cancel", "🤳"),
    THEREMIN("Air Theremin", "Play music with your hand, no instrument", "🎶")
}

@Composable
fun HomeScreen(onOpen: (AppScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Smart Classifier", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choose a feature", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))

        AppScreen.values().forEach { screen ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(screen) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(screen.icon, fontSize = 32.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(screen.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(screen.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}