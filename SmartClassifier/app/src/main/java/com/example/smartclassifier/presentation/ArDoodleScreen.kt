package com.example.smartclassifier.presentation

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.smartclassifier.presentation.ar.ArDoodleController
import com.example.smartclassifier.presentation.ar.LightPattern
import com.example.smartclassifier.presentation.util.ArPermissionGate
import com.example.smartclassifier.presentation.util.findActivity
import com.example.smartclassifier.presentation.util.hasPermission

private val palette = listOf(
    0xFFFFFFFF, 0xFFFF5252, 0xFFFFEB3B, 0xFF69F0AE,
    0xFF40C4FF, 0xFF7C4DFF, 0xFFFF4081, 0xFF000000
).map { it.toInt() }

/** AR Doodle: draw lines or string lights in 3D, record video. */
@Composable
fun ArDoodleScreen(viewModel: ArDoodleViewModel = hiltViewModel()) {
    ArPermissionGate { requestPermissions -> ArDoodleContent(viewModel, requestPermissions) }
}

@Composable
private fun ArDoodleContent(viewModel: ArDoodleViewModel, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    val activity = remember { context.findActivity() }
    if (activity == null) {
        Text("AR Doodle is not available here", modifier = Modifier.padding(24.dp))
        return
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }

    val controller = remember {
        ArDoodleController(
            activity = activity,
            onTracking = viewModel::onTracking,
            onStrokeCount = viewModel::onStrokeCount,
            onElapsed = viewModel::onElapsed,
            onRecordingChanged = viewModel::onRecordingChanged,
            onMessage = viewModel::postMessage
        )
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setError(controller.resume())
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.pause()
            controller.release()
            viewModel.onStrokeCount(0)
            viewModel.onTracking(false)
        }
    }

    LaunchedEffect(state.color, state.brush, state.lights, state.lightPattern) {
        controller.colorArgb = state.color
        controller.brush = state.brush
        controller.lightsMode = state.lights
        controller.lightPattern = LightPattern.values()[state.lightPattern]
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { controller.glView }, modifier = Modifier.fillMaxSize())

        // Drawing layer: finger -> 3D stroke
        if (state.error == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(controller) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            controller.beginStroke(down.position.x, down.position.y)
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        controller.addPoint(change.position.x, change.position.y)
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            controller.endStroke()
                        }
                    }
            )
        }

        // Status chip
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            val text = when {
                state.error != null -> state.error!!
                state.isRecording -> "● REC  ${formatTime(state.elapsedMs)}"
                !state.tracking -> "Move your phone slowly to scan the surroundings"
                state.strokeCount == 0 -> "Draw in the air or on a surface"
                else -> "${state.strokeCount} doodle(s)"
            }
            Text(
                text,
                color = if (state.isRecording) Color(0xFFFF5252) else Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (state.error == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Brush style: Line / Lights (+ blink pattern)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        TextButton(onClick = { viewModel.setLights(false) }) {
                            Text("LINE", color = if (!state.lights) Color(0xFFFF9800) else Color.White)
                        }
                        TextButton(onClick = { viewModel.setLights(true) }) {
                            Text("LIGHTS", color = if (state.lights) Color(0xFFFF9800) else Color.White)
                        }
                    }
                    if (state.lights) {
                        Row {
                            listOf("CHASE", "ALT", "TWINKLE").forEachIndexed { index, label ->
                                TextButton(onClick = { viewModel.setLightPattern(index) }) {
                                    Text(
                                        label,
                                        color = if (state.lightPattern == index) Color(0xFFFF9800) else Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Colours
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.forEach { argb ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(Color(argb), CircleShape)
                                .border(
                                    width = if (argb == state.color) 3.dp else 1.dp,
                                    color = if (argb == state.color) Color(0xFFFF9800) else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { viewModel.setColor(argb) }
                        )
                    }
                }

                // Brush size
                Slider(
                    value = state.brush,
                    onValueChange = viewModel::setBrush,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = controller::undo, enabled = state.strokeCount > 0) { Text("Undo") }

                    // Record / stop
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .background(
                                Color(0xFFFF1744),
                                if (state.isRecording) RoundedCornerShape(10.dp) else CircleShape
                            )
                            .clickable {
                                if (state.isRecording) {
                                    controller.stopRecording()
                                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                ) {
                                    requestPermissions()
                                    viewModel.postMessage("Allow storage permission, then tap record again")
                                } else {
                                    controller.startRecording(context.hasPermission(Manifest.permission.RECORD_AUDIO))
                                }
                            }
                    )

                    OutlinedButton(onClick = controller::clear, enabled = state.strokeCount > 0) { Text("Clear") }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}