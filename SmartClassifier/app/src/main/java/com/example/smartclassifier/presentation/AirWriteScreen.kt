package com.example.smartclassifier.presentation

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.smartclassifier.presentation.ar.InkPoint
import com.example.smartclassifier.presentation.util.ArPermissionGate
import com.example.smartclassifier.presentation.util.findActivity
import com.example.smartclassifier.presentation.util.hasPermission
import kotlinx.coroutines.delay

private val palette = listOf(
    0xFFFFFFFF, 0xFFFF5252, 0xFFFFEB3B, 0xFF69F0AE,
    0xFF40C4FF, 0xFF7C4DFF, 0xFFFF4081, 0xFF000000
).map { it.toInt() }

/** Air-Write: handwriting in the air -> 3D text pinned in the real world. */
@Composable
fun AirWriteScreen(viewModel: AirWriteViewModel = hiltViewModel()) {
    ArPermissionGate { requestPermissions -> AirWriteContent(viewModel, requestPermissions) }
}

@Composable
private fun AirWriteContent(viewModel: AirWriteViewModel, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    val activity = remember { context.findActivity() }
    if (activity == null) {
        Text("Air-Write is not available here", modifier = Modifier.padding(24.dp))
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
            onStrokeCount = viewModel::onItemCount,
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
            viewModel.onItemCount(0)
            viewModel.onTracking(false)
        }
    }

    // ---------------- handwriting ink (screen pixels)
    val ink = remember { mutableStateListOf<List<InkPoint>>() }
    val live = remember { mutableStateListOf<InkPoint>() }
    var touching by remember { mutableStateOf(false) }
    var inkVersion by remember { mutableIntStateOf(0) }

    fun convert() {
        if (ink.isEmpty() || state.isRecognizing) return
        val strokes = ink.toList()
        ink.clear()
        val pts = strokes.flatten()
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val color = state.color
        viewModel.recognize(strokes) { text ->
            if (text.isBlank()) {
                viewModel.postMessage("Couldn't read the writing, try again")
            } else {
                controller.placeText(
                    text, color, (minX + maxX) / 2f, (minY + maxY) / 2f, maxX - minX, maxY - minY
                )
            }
        }
    }

    // auto-convert when the finger rests for a moment
    LaunchedEffect(inkVersion) {
        if (inkVersion > 0 && ink.isNotEmpty()) {
            delay(1800)
            if (!touching) convert()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { controller.glView }, modifier = Modifier.fillMaxSize())

        // Writing layer
        if (state.error == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(controller) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            touching = true
                            live.clear()
                            live.add(InkPoint(down.position.x, down.position.y, System.currentTimeMillis()))
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        live.add(
                                            InkPoint(change.position.x, change.position.y, System.currentTimeMillis())
                                        )
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (live.size > 1) ink.add(live.toList())
                            live.clear()
                            touching = false
                            inkVersion++
                        }
                    }
            )
        }

        // Ink preview
        Canvas(Modifier.fillMaxSize()) {
            val inkColor = Color(state.color)
            val style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun drawInk(points: List<InkPoint>) {
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(path, inkColor, style = style)
            }
            ink.forEach { drawInk(it) }
            drawInk(live.toList())
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
                state.isRecognizing -> "Reading your handwriting… (first time downloads the model)"
                !state.tracking -> "Move your phone slowly to scan the surroundings"
                else -> "Write in the air — it becomes 3D text"
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
                // Language + convert
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        TextButton(onClick = { viewModel.setLanguage("en-US") }) {
                            Text("EN", color = if (state.language == "en-US") Color(0xFFFF9800) else Color.White)
                        }
                        TextButton(onClick = { viewModel.setLanguage("hi-IN") }) {
                            Text("हिं", color = if (state.language == "hi-IN") Color(0xFFFF9800) else Color.White)
                        }
                    }
                    Button(
                        onClick = { convert() },
                        enabled = ink.isNotEmpty() && !state.isRecognizing
                    ) { Text("Convert") }
                }

                // Text colour
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

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = controller::undo, enabled = state.itemCount > 0) { Text("Undo") }

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

                    OutlinedButton(
                        onClick = {
                            controller.clear()
                            ink.clear()
                            live.clear()
                        },
                        enabled = state.itemCount > 0 || ink.isNotEmpty()
                    ) { Text("Clear") }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}