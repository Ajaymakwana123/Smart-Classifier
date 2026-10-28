package com.example.smartclassifier.presentation

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.scale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.smartclassifier.presentation.ar.HandPose
import com.example.smartclassifier.presentation.ar.HandTracker
import com.example.smartclassifier.presentation.ar.InkPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.hypot

private val palette = listOf(
    0xFFFFFFFF, 0xFFFF5252, 0xFFFFEB3B, 0xFF69F0AE,
    0xFF40C4FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFF9800
).map { it.toInt() }

/**
 * Hand Air-Write Screen:
 * • Front/Back camera support.
 * • Accurate hand landmark tracking (index fingertip).
 * • Clear expression/gesture triggers (Pinch, Point, or Hold-to-Draw Button).
 * • Anti-jitter coordinate smoothing & distance thresholding.
 * • User-controlled conversion: never prematurely wipes user's writing!
 * • Stroke undo & multi-candidate suggestions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HandAirWriteScreen(viewModel: HandAirWriteViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    // ── Permissions ──────────────────────────────────────────────────────────
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { cameraGranted = granted(Manifest.permission.CAMERA) }

    val perms = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !asked) {
            asked = true
            permLauncher.launch(perms)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }

    if (!cameraGranted) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission is required for Hand Air-Write", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(perms) }) { Text("Grant permission") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
            }) { Text("Open Settings") }
        }
        return
    }

    // ── Camera State ─────────────────────────────────────────────────────────
    var isFrontCamera by rememberSaveable { mutableStateOf(true) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Hand tracking state
    var cursorScreenPos by remember { mutableStateOf<Offset?>(null) }
    var isDrawingNow by remember { mutableStateOf(false) }
    var isHoldingDrawButton by remember { mutableStateOf(false) }

    // Screen dimensions
    var canvasW by remember { mutableFloatStateOf(1080f) }
    var canvasH by remember { mutableFloatStateOf(1920f) }

    // Strokes data
    val completedStrokes = remember { mutableStateListOf<List<InkPoint>>() }
    val currentLiveStroke = remember { mutableStateListOf<InkPoint>() }
    var strokeChangeCounter by remember { mutableIntStateOf(0) }

    // Auto-convert countdown state (when auto-convert is explicitly enabled)
    var countdownRemaining by remember { mutableFloatStateOf(0f) }

    // Label currently held by the hand in GRAB mode
    var grabbedId by remember { mutableStateOf<Long?>(null) }

    // Non-UI pen bookkeeping (smoothing + debounce) – plain object, no recomposition
    val pen = remember { PenTracker() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // MediaPipe 21-point hand tracker (model auto-downloads once)
    val tracker = remember { HandTracker(context.applicationContext) }
    var modelLoading by remember { mutableStateOf(true) }

    LaunchedEffect(tracker) {
        val err = withContext(Dispatchers.IO) { tracker.prepare() }
        modelLoading = false
        if (err != null) viewModel.postMessage(err)
    }

    // Release detector/executor only when the screen is really left (NOT on camera flip)
    DisposableEffect(Unit) {
        onDispose {
            tracker.close()
            analysisExecutor.shutdown()
        }
    }

    fun endStroke() {
        isDrawingNow = false
        viewModel.setDrawing(false)
        if (currentLiveStroke.size >= 2) {
            completedStrokes.add(currentLiveStroke.toList())
            strokeChangeCounter++
        }
        currentLiveStroke.clear()
        pen.downFrames = 0
        pen.upFrames = 0
        pen.pending.clear()
    }

    // GRAB mode: pinch on a label -> drag it with your hand -> release to drop
    fun handleGrab(cur: Offset, pinching: Boolean) {
        if (isDrawingNow) endStroke()
        if (pinching) {
            pen.downFrames++
            pen.upFrames = 0
        } else {
            pen.upFrames++
            pen.downFrames = 0
        }

        val g = grabbedId
        if (g == null) {
            if (pen.downFrames >= 2) {
                val hit = state.labels.minByOrNull {
                    hypot(it.x * canvasW - cur.x, (it.y * canvasH - 30f) - cur.y)
                }
                if (hit != null &&
                    hypot(hit.x * canvasW - cur.x, (hit.y * canvasH - 30f) - cur.y) < 200f
                ) {
                    grabbedId = hit.id
                }
            }
        } else {
            if (pinching) {
                viewModel.moveLabel(
                    g,
                    (cur.x / canvasW).coerceIn(0.08f, 0.92f),
                    ((cur.y + 30f) / canvasH).coerceIn(0.05f, 0.95f)
                )
            } else if (pen.upFrames >= 3) {
                grabbedId = null
            }
        }

        viewModel.setCurrentGesture(
            if (grabbedId != null) "✊ Holding label — move & release"
            else "🤏 Pinch on a text label to grab it"
        )
    }

    // Called on MAIN thread for every analysed camera frame
    fun handleHand(pose: HandPose?) {
        // ── No hand ──
        if (pose == null) {
            viewModel.setHandDetected(false)
            cursorScreenPos = null
            pen.downFrames = 0
            pen.upFrames++
            pen.pending.clear()
            if (isDrawingNow && pen.upFrames >= 6) endStroke()
            if (pen.upFrames >= 6) grabbedId = null
            if (!isDrawingNow) pen.sx = -1f
            return
        }
        viewModel.setHandDetected(true)

        val mode = state.gestureMode

        // Pen tip: pinch -> centre of thumb+index tips (very stable), others -> index fingertip
        val rawX: Float
        val rawY: Float
        if (mode == GestureMode.PINCH || mode == GestureMode.GRAB) {
            rawX = pose.pinchCenterX
            rawY = pose.pinchCenterY
        } else {
            rawX = pose.x[8]
            rawY = pose.y[8]
        }

        // Analysis image -> screen (PreviewView FILL_CENTER = scale to cover + centre crop, front cam mirrored)
        val aw = pose.imgW
        val ah = pose.imgH
        val scale = maxOf(canvasW / aw, canvasH / ah)
        val offX = (aw * scale - canvasW) / 2f
        val offY = (ah * scale - canvasH) / 2f
        val mirroredX = if (isFrontCamera) aw - rawX else rawX
        val targetX = mirroredX * scale - offX
        val targetY = rawY * scale - offY

        // Speed-adaptive smoothing: slow = strong smoothing (no jitter), fast = almost no lag (shape stays true)
        if (pen.sx < 0f) {
            pen.sx = targetX
            pen.sy = targetY
        } else {
            val speed = hypot(targetX - pen.sx, targetY - pen.sy)
            val a = (0.25f + speed / 90f).coerceIn(0.25f, 0.90f)
            pen.sx += a * (targetX - pen.sx)
            pen.sy += a * (targetY - pen.sy)
        }
        val cur = Offset(pen.sx, pen.sy)
        cursorScreenPos = cur

        // ── Gesture decision (real landmark geometry + hysteresis) ──
        val wantRaw: Boolean = when (mode) {
            GestureMode.PINCH -> {
                val r = pose.pinchRatio
                if (isDrawingNow) r < 0.60f else r < 0.35f
            }

            GestureMode.INDEX_POINT -> {
                val idx = pose.indexExt
                val oth = pose.maxOtherExt
                if (isDrawingNow) (idx > 1.15f && oth < 1.30f) else (idx > 1.30f && oth < 1.15f)
            }

            GestureMode.HOLD_BUTTON -> isHoldingDrawButton
            GestureMode.GRAB -> {
                val r = pose.pinchRatio
                if (grabbedId != null) r < 0.60f else r < 0.35f
            }
        }

        if (mode == GestureMode.GRAB) {
            handleGrab(cur, wantRaw)
            return
        }

        // Debounce: need N consecutive frames to start/stop -> no accidental writing, no broken strokes
        val need = if (mode == GestureMode.HOLD_BUTTON) 1 else 3
        if (wantRaw) {
            pen.downFrames++
            pen.upFrames = 0
        } else {
            pen.upFrames++
            pen.downFrames = 0
        }

        val now = System.currentTimeMillis()
        if (!isDrawingNow) {
            if (wantRaw) {
                pen.pending.add(InkPoint(cur.x, cur.y, now))
                if (pen.downFrames >= need) {
                    isDrawingNow = true
                    viewModel.setDrawing(true)
                    currentLiveStroke.clear()
                    currentLiveStroke.addAll(pen.pending)
                    pen.pending.clear()
                }
            } else {
                pen.pending.clear()
            }
        } else {
            if (wantRaw) {
                val last = currentLiveStroke.lastOrNull()
                if (last == null || hypot(cur.x - last.x, cur.y - last.y) >= 5f) {
                    currentLiveStroke.add(InkPoint(cur.x, cur.y, now))
                }
            } else if (pen.upFrames >= need) {
                endStroke()
            }
        }

        viewModel.setCurrentGesture(
            when (mode) {
                GestureMode.PINCH ->
                    if (isDrawingNow) "🤏 Pinching (Writing)" else "✋ Open Hand (Hovering) — pinch to write"

                GestureMode.INDEX_POINT ->
                    if (isDrawingNow) "☝️ Pointing (Writing)" else "✋ Hovering — fold other fingers, point index"

                GestureMode.HOLD_BUTTON ->
                    if (isDrawingNow) "✍️ Writing (Button Held)" else "✋ Hovering (Press button to write)"

                GestureMode.GRAB -> state.currentGestureName
            }
        )
    }

    val onHandFrame = rememberUpdatedState<(HandPose?) -> Unit>(newValue = { handleHand(it) })

    // Bind CameraX
    DisposableEffect(lifecycleOwner, isFrontCamera) {
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

            analysis.setAnalyzer(analysisExecutor) { proxy: ImageProxy ->
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
                    mainHandler.post { onHandFrame.value(pose) }
                } catch (_: Exception) {
                    // ignore single bad frame
                } finally {
                    proxy.close()
                }
            }

            try {
                val selector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                viewModel.postMessage("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
        }
    }

    // Auto-convert countdown: only runs if user EXPLICITLY turned ON auto-convert
    LaunchedEffect(strokeChangeCounter, state.autoConvertEnabled, isDrawingNow) {
        if (state.autoConvertEnabled && completedStrokes.isNotEmpty() && !isDrawingNow) {
            countdownRemaining = 5f
            while (countdownRemaining > 0f) {
                delay(100)
                countdownRemaining -= 0.1f
                if (isDrawingNow) {
                    countdownRemaining = 0f
                    break
                }
            }
            if (countdownRemaining <= 0f && completedStrokes.isNotEmpty() && !isDrawingNow && !state.isRecognizing) {
                val strokes = completedStrokes.toList()
                val pts = strokes.flatten()
                val cx = ((pts.minOf { it.x } + pts.maxOf { it.x }) / 2f) / canvasW
                val cy = ((pts.minOf { it.y } + pts.maxOf { it.y }) / 2f) / canvasH
                completedStrokes.clear()
                viewModel.recognize(strokes, cx, cy)
            }
        } else {
            countdownRemaining = 0f
        }
    }

    // Manual convert trigger function
    fun doConvert() {
        if (completedStrokes.isEmpty() && currentLiveStroke.isEmpty()) {
            viewModel.postMessage("First write something in the air!")
            return
        }
        val allStrokes = buildList {
            addAll(completedStrokes)
            if (currentLiveStroke.size >= 2) add(currentLiveStroke.toList())
        }
        if (allStrokes.isEmpty()) return

        val pts = allStrokes.flatten()
        val cx = ((pts.minOf { it.x } + pts.maxOf { it.x }) / 2f) / canvasW
        val cy = ((pts.minOf { it.y } + pts.maxOf { it.y }) / 2f) / canvasH

        completedStrokes.clear()
        currentLiveStroke.clear()
        countdownRemaining = 0f

        viewModel.recognize(allStrokes, cx, cy)
    }

    // ── UI Screen ────────────────────────────────────────────────────────────
    Box(Modifier
        .fillMaxSize()
        .background(Color.Black)) {

        // Camera Preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Ink and Cursor Canvas
        Canvas(Modifier.fillMaxSize()) {
            canvasW = size.width
            canvasH = size.height

            val inkColor = Color(state.color)
            val strokeStyle = Stroke(
                width = 10f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            fun renderStroke(points: List<InkPoint>) {
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    if (points.size == 2) {
                        lineTo(points[1].x, points[1].y)
                    } else {
                        for (i in 1 until points.size - 1) {
                            val mx = (points[i].x + points[i + 1].x) / 2f
                            val my = (points[i].y + points[i + 1].y) / 2f
                            quadraticBezierTo(points[i].x, points[i].y, mx, my)
                        }
                        lineTo(points.last().x, points.last().y)
                    }
                }
                // Glow effect under stroke
                drawPath(
                    path,
                    inkColor.copy(alpha = 0.35f),
                    style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // Crisp stroke
                drawPath(path, inkColor, style = strokeStyle)
            }

            completedStrokes.forEach { renderStroke(it) }
            renderStroke(currentLiveStroke.toList())

            // Cursor on fingertip
            cursorScreenPos?.let { pos ->
                if (isDrawingNow) {
                    // Pulsing pen cursor
                    drawCircle(
                        color = Color(0x664CAF50),
                        radius = 26f,
                        center = pos
                    )
                    drawCircle(
                        color = Color(0xFF4CAF50),
                        radius = 12f,
                        center = pos
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = pos
                    )
                } else {
                    // Glowing target hover cursor
                    drawCircle(
                        color = Color(0x44FFC107),
                        radius = 22f,
                        center = pos,
                        style = Stroke(width = 3f)
                    )
                    drawCircle(
                        color = Color(0xFFFFC107),
                        radius = 6f,
                        center = pos
                    )
                }
            }

            // Floating 3D Text Labels
            state.labels.forEach { label ->
                val lx = label.x * size.width
                val ly = label.y * size.height
                drawContext.canvas.nativeCanvas.apply {
                    val grabbed = label.id == grabbedId
                    val paintBg = android.graphics.Paint().apply {
                        color = if (grabbed) android.graphics.Color.argb(230, 255, 152, 0)
                        else android.graphics.Color.argb(160, 20, 20, 20)
                        style = android.graphics.Paint.Style.FILL
                        isAntiAlias = true
                    }
                    val paintText = android.graphics.Paint().apply {
                        color = label.color
                        textSize = 68f
                        isAntiAlias = true
                        isFakeBoldText = true
                        setShadowLayer(10f, 3f, 3f, android.graphics.Color.BLACK)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }

                    val textBounds = android.graphics.Rect()
                    paintText.getTextBounds(label.text, 0, label.text.length, textBounds)
                    val padH = 32f
                    val padV = 20f
                    val rectF = android.graphics.RectF(
                        lx - textBounds.width() / 2f - padH,
                        ly - textBounds.height() - padV,
                        lx + textBounds.width() / 2f + padH,
                        ly + padV
                    )
                    drawRoundRect(rectF, 24f, 24f, paintBg)
                    drawText(label.text, lx, ly, paintText)
                }
            }
        }

        // ── TOP BAR: Mode Selector + Live Gesture Pill + Camera Flip ──────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 12.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Live Gesture Status Chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isDrawingNow) Color(0xFF1B5E20).copy(alpha = 0.85f)
                    else Color.Black.copy(alpha = 0.65f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isDrawingNow) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                state.isRecognizing -> "✍ Recognizing text..."
                                modelLoading -> "⏳ Loading hand model..."
                                !state.handDetected -> "❓ Wave hand at camera"
                                else -> state.currentGestureName
                            },
                            color = if (isDrawingNow) Color(0xFF69F0AE) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Camera flip button
                IconButton(
                    onClick = { isFrontCamera = !isFrontCamera },
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Flip Camera",
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Expression / Gesture Mode Tabs
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GestureMode.values().forEach { mode ->
                    val isSelected = state.gestureMode == mode
                    val animColor by animateColorAsState(
                        if (isSelected) Color(0xFFFF9800) else Color.Transparent,
                        label = "modeTab"
                    )

                    Box(
                        modifier = Modifier
                            .background(animColor, RoundedCornerShape(16.dp))
                            .clickable {
                                grabbedId = null
                                viewModel.setGestureMode(mode)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.icon, fontSize = 14.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                mode.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }
            }
        }

        // ── Auto-Convert Countdown Toast Banner ──────────────────────────────
        AnimatedVisibility(
            visible = countdownRemaining > 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 120.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.75f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Auto-converting in ${countdownRemaining.toInt() + 1}s...",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { countdownRemaining = 0f }) {
                        Text("Pause", color = Color(0xFFFF9800), fontSize = 12.sp)
                    }
                }
            }
        }

        // ── BOTTOM CONTROLS & ACTION BUTTONS ──────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Hold-to-Draw Button (when in HOLD_BUTTON mode)
            if (state.gestureMode == GestureMode.HOLD_BUTTON) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (isHoldingDrawButton) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(52.dp)
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    isHoldingDrawButton = true
                                    true
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    isHoldingDrawButton = false
                                    true
                                }

                                else -> false
                            }
                        }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isHoldingDrawButton) "✏️ Writing in Air... (Release to Stop)" else "🔘 Press & Hold to Write",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Suggestions row (if any recognized candidates exist)
            if (state.candidates.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Suggestions: ", color = Color.Gray, fontSize = 11.sp)
                    state.candidates.take(3).forEach { cand ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable { viewModel.selectCandidate(cand) }
                        ) {
                            Text(
                                cand,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Main Action Row: Language + Big Convert Button + Stroke Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Language Switch
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                        .padding(2.dp)
                ) {
                    Text(
                        "EN",
                        color = if (state.language == "en-US") Color(0xFFFF9800) else Color.White,
                        fontWeight = if (state.language == "en-US") FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { viewModel.setLanguage("en-US") }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Text(
                        "हिं",
                        color = if (state.language == "hi-IN") Color(0xFFFF9800) else Color.White,
                        fontWeight = if (state.language == "hi-IN") FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { viewModel.setLanguage("hi-IN") }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // Primary CONVERT Button: Beautiful, glowing, user-controlled!
                val hasStrokes = completedStrokes.isNotEmpty() || currentLiveStroke.isNotEmpty()
                Button(
                    onClick = { doConvert() },
                    enabled = hasStrokes && !state.isRecognizing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800),
                        disabledContainerColor = Color.DarkGray
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    if (state.isRecognizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Reading...", color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Text(
                            "✨ Convert to Text",
                            color = if (hasStrokes) Color.Black else Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Auto-convert switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Auto",
                        color = if (state.autoConvertEnabled) Color(0xFFFF9800) else Color.Gray,
                        fontSize = 11.sp
                    )
                    Switch(
                        checked = state.autoConvertEnabled,
                        onCheckedChange = { viewModel.setAutoConvertEnabled(it) },
                        modifier = Modifier.scale(0.75f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Palette row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                palette.forEach { argb ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .padding(horizontal = 2.dp)
                            .background(Color(argb), CircleShape)
                            .border(
                                width = if (argb == state.color) 2.5.dp else 1.dp,
                                color = if (argb == state.color) Color(0xFFFF9800) else Color.Gray.copy(
                                    alpha = 0.5f
                                ),
                                shape = CircleShape
                            )
                            .clickable { viewModel.setColor(argb) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Utility buttons: Undo Stroke | Labels Count | Clear All
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Undo Stroke
                OutlinedButton(
                    onClick = {
                        if (currentLiveStroke.isNotEmpty()) {
                            currentLiveStroke.clear()
                        } else if (completedStrokes.isNotEmpty()) {
                            completedStrokes.removeAt(completedStrokes.lastIndex)
                            strokeChangeCounter++
                        }
                    },
                    enabled = completedStrokes.isNotEmpty() || currentLiveStroke.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("↩️", fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("Undo Stroke", color = Color.White, fontSize = 11.sp)
                }

                // Stroke & Label Status
                Text(
                    text = "${completedStrokes.size} strokes • ${state.labels.size} labels",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )

                // Clear
                OutlinedButton(
                    onClick = {
                        completedStrokes.clear()
                        currentLiveStroke.clear()
                        countdownRemaining = 0f
                        viewModel.clearLabels()
                    },
                    enabled = completedStrokes.isNotEmpty() || currentLiveStroke.isNotEmpty() || state.labels.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            }
        }
    }
}

/** Plain (non-Compose) bookkeeping for smoothing + gesture debounce. */
private class PenTracker {
    var sx = -1f
    var sy = -1f
    var downFrames = 0
    var upFrames = 0
    val pending = mutableListOf<InkPoint>()
}