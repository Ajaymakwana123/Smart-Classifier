package com.example.smartclassifier.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.smartclassifier.presentation.ar.HandPose
import com.example.smartclassifier.presentation.ar.HandTracker
import com.example.smartclassifier.presentation.ar.ThereminEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class MusicScale(val label: String, val semis: List<Int>?) {
    SMOOTH("🌊 Smooth", null),
    MAJOR("🎹 Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    PENTA("🎋 Pentatonic", listOf(0, 2, 4, 7, 9)),
    YAMAN("🪕 Raag Yaman", listOf(0, 2, 4, 6, 7, 9, 11)),
    BHAIRAVI("🌅 Raag Bhairavi", listOf(0, 1, 3, 5, 7, 8, 10))
}

private val waveNames = listOf("Sine", "Square", "Saw", "Triangle")
private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun midiToFreq(m: Int) = 440f * 2f.pow((m - 69) / 12f)
private fun midiName(m: Int) = noteNames[((m % 12) + 12) % 12] + (m / 12 - 1)

private fun buildNotes(scale: MusicScale): List<Int> {
    val semis = scale.semis ?: return emptyList()
    val out = mutableListOf<Int>()
    for (oct in 0 until 3) for (s in semis) out.add(48 + 12 * oct + s)
    out.add(84)
    return out
}

private class ThereminTrack {
    var sx = -1f
    var sy = -1f
    var lastIdx = -1
}

@Composable
fun ThereminScreen() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted = granted(Manifest.permission.CAMERA) }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !asked) {
            asked = true
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!cameraGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission is required for Air Theremin", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
            }) { Text("Open Settings") }
        }
        return
    }

    // ── State ────────────────────────────────────────────────────────────────
    var scale by rememberSaveable { mutableIntStateOf(MusicScale.PENTA.ordinal) }
    var waveIdx by rememberSaveable { mutableIntStateOf(0) }
    val musicScale = MusicScale.values()[scale]
    val notes = remember(scale) { buildNotes(musicScale) }

    var canvasW by remember { mutableFloatStateOf(1080f) }
    var canvasH by remember { mutableFloatStateOf(1920f) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var ampUi by remember { mutableFloatStateOf(0f) }
    var activeLane by remember { mutableIntStateOf(-1) }
    var noteLabel by remember { mutableStateOf("—") }
    var handSeen by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var modelLoading by remember { mutableStateOf(true) }
    val trail = remember { mutableStateListOf<Offset>() }

    val track = remember { ThereminTrack() }
    val engine = remember { ThereminEngine() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val tracker = remember { HandTracker(context.applicationContext) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(waveIdx) { engine.wave = waveIdx }

    LaunchedEffect(tracker) {
        val err = withContext(Dispatchers.IO) { tracker.prepare() }
        modelLoading = false
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) {
        engine.start()
        onDispose {
            engine.stop()
            tracker.close()
            executor.shutdown()
        }
    }

    // ── Per-frame: hand -> pitch + volume (MAIN thread) ──────────────────────
    fun onFrame(pose: HandPose?) {
        if (pose == null) {
            handSeen = false
            engine.targetAmp = 0f
            ampUi = 0f
            cursor = null
            track.sx = -1f
            activeLane = -1
            if (trail.isNotEmpty()) trail.removeAt(0)
            return
        }
        handSeen = true

        // Palm centre (landmark 9) – steady, not affected by pinch
        val aw = pose.imgW
        val ah = pose.imgH
        val sc = maxOf(canvasW / aw, canvasH / ah)
        val offX = (aw * sc - canvasW) / 2f
        val offY = (ah * sc - canvasH) / 2f
        val tx = (aw - pose.x[9]) * sc - offX   // front camera mirrored
        val ty = pose.y[9] * sc - offY

        if (track.sx < 0f) {
            track.sx = tx
            track.sy = ty
        } else {
            track.sx += 0.4f * (tx - track.sx)
            track.sy += 0.4f * (ty - track.sy)
        }
        val nx = (track.sx / canvasW).coerceIn(0f, 0.999f)
        val ny = (track.sy / canvasH).coerceIn(0f, 1f)

        // Pitch
        val freq: Float
        val midi: Int
        if (notes.isEmpty()) {
            freq = 130.81f * 2f.pow(nx * 3f)                       // C3 -> C6 smooth glide
            midi = (69 + 12 * ln(freq / 440f) / ln(2f)).roundToInt()
            activeLane = -1
        } else {
            val pos = nx * notes.size
            var idx = pos.toInt().coerceIn(0, notes.size - 1)
            // hysteresis: avoid flicker on lane borders
            if (track.lastIdx in notes.indices && abs(pos - (track.lastIdx + 0.5f)) < 0.62f) {
                idx = track.lastIdx
            }
            track.lastIdx = idx
            activeLane = idx
            midi = notes[idx]
            freq = midiToFreq(midi)
        }

        // Volume: hand high = loud, low = quiet. Pinch = mute.
        val pinch = pose.pinchRatio < 0.35f
        muted = pinch
        val amp = if (pinch) 0f else ((0.88f - ny) / 0.62f).coerceIn(0f, 1f)

        engine.targetFreq = freq
        engine.targetAmp = amp
        ampUi = amp
        noteLabel = midiName(midi)
        cursor = Offset(track.sx, track.sy)

        trail.add(Offset(track.sx, track.sy))
        if (trail.size > 24) trail.removeAt(0)
    }
    val onFrameState = rememberUpdatedState<(HandPose?) -> Unit>(newValue = { onFrame(it) })

    // ── Camera (front) ───────────────────────────────────────────────────────
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            val p = future.get()
            provider = p
            p.unbindAll()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                try {
                    if (!tracker.ready) return@setAnalyzer
                    val rotation = proxy.imageInfo.rotationDegrees
                    val raw = proxy.toBitmap()
                    val upright = if (rotation != 0) {
                        Bitmap.createBitmap(
                            raw, 0, 0, raw.width, raw.height,
                            Matrix().apply { postRotate(rotation.toFloat()) }, true
                        )
                    } else raw
                    val pose = tracker.detect(upright, SystemClock.uptimeMillis())
                    mainHandler.post { onFrameState.value(pose) }
                } catch (_: Exception) {
                } finally {
                    proxy.close()
                }
            }

            try {
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Canvas(Modifier.fillMaxSize()) {
            canvasW = size.width
            canvasH = size.height

            // Note lanes
            if (notes.isNotEmpty()) {
                val laneW = size.width / notes.size
                for (i in notes.indices) {
                    val active = i == activeLane
                    val isRoot = notes[i] % 12 == 0
                    val c = when {
                        active -> Color(0xFFFF9800).copy(alpha = 0.45f)
                        isRoot -> Color(0xFF40C4FF).copy(alpha = 0.16f)
                        i % 2 == 0 -> Color.White.copy(alpha = 0.07f)
                        else -> Color.White.copy(alpha = 0.03f)
                    }
                    drawRect(c, Offset(i * laneW, 0f), androidx.compose.ui.geometry.Size(laneW, size.height))
                }
            } else {
                // smooth mode: 3 octave markers
                for (o in 0..3) {
                    val x = size.width * o / 3f
                    drawLine(Color.White.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 2f)
                }
            }

            // Fading trail
            trail.forEachIndexed { i, o ->
                val k = (i + 1) / trail.size.toFloat()
                drawCircle(Color(0xFFFF9800).copy(alpha = 0.35f * k), 6f + 16f * k, o)
            }

            // Orb at hand: bigger/brighter = louder
            cursor?.let { pos ->
                val r = 26f + 60f * ampUi
                val col = if (muted) Color(0xFFFF5252) else Color(0xFFFFC107)
                drawCircle(col.copy(alpha = 0.18f), r * 1.8f, pos)
                drawCircle(col.copy(alpha = 0.40f), r, pos)
                drawCircle(Color.White, 10f, pos)
            }
        }

        // Top: note + status
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
                Text(
                    text = when {
                        modelLoading -> "⏳ Loading hand model..."
                        !handSeen -> "🖐 Show your hand to start playing"
                        muted -> "🔇 Muted (pinch released = sound)"
                        else -> "🎵 Playing"
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            if (handSeen && !muted) {
                Text(
                    noteLabel,
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom: scale + wave pickers
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(12.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicScale.values().forEach { s ->
                    val sel = s.ordinal == scale
                    Text(
                        s.label,
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(if (sel) Color(0xFFFF9800) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .clickable { scale = s.ordinal; track.lastIdx = -1 }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                waveNames.forEachIndexed { i, n ->
                    val sel = i == waveIdx
                    Text(
                        n,
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(if (sel) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .clickable { waveIdx = i }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "← → pitch   •   ↑ ↓ volume   •   🤏 pinch = mute",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}