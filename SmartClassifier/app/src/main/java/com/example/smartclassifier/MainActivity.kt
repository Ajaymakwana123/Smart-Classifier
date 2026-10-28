package com.example.smartclassifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.smartclassifier.presentation.AirWriteScreen
import com.example.smartclassifier.presentation.AppScreen
import com.example.smartclassifier.presentation.ArDoodleScreen
import com.example.smartclassifier.presentation.ClassifierScreen
import com.example.smartclassifier.presentation.EmojiScreen
import com.example.smartclassifier.presentation.GestureSelfieScreen
import com.example.smartclassifier.presentation.HandAirWriteScreen
import com.example.smartclassifier.presentation.HomeScreen
import com.example.smartclassifier.presentation.StickerScreen
import com.example.smartclassifier.presentation.ThereminScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    // null = Home (feature buttons)
                    var currentName by rememberSaveable { mutableStateOf<String?>(null) }
                    val current = currentName?.let { AppScreen.valueOf(it) }

                    BackHandler(enabled = current != null) { currentName = null }

                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        if (current == null) {
                            HomeScreen(onOpen = { currentName = it.name })
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { currentName = null }) { Text("← Back") }
                                Text(current.title, fontWeight = FontWeight.Bold)
                            }
                            Box(Modifier.weight(1f)) {
                                when (current) {
                                    AppScreen.ANALYZE -> ClassifierScreen()
                                    AppScreen.STICKER -> StickerScreen()
                                    AppScreen.AR_DOODLE -> ArDoodleScreen()
                                    AppScreen.AIR_WRITE -> AirWriteScreen()
                                    AppScreen.AR_EMOJI -> EmojiScreen()
                                    AppScreen.HAND_AIR_WRITE -> HandAirWriteScreen()
                                    AppScreen.GESTURE_SELFIE -> GestureSelfieScreen()
                                    AppScreen.THEREMIN -> ThereminScreen()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}