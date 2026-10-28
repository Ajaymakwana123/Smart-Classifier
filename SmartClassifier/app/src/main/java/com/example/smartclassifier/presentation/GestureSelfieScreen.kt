package com.example.smartclassifier.presentation

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.smartclassifier.presentation.ar.HandPose
import com.example.smartclassifier.presentation.ar.HandTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot

private enum class Sign { NONE, PEACE, PALM }

private fun fingerExt(p: HandPose, tip: Int, pip: Int): Float {
    val a = hypot(p.x[tip] - p.x[0], p.y[tip] - p.y[0])
    val b = hypot(p.x[pip] - p.x[0], p.y[pip] - p.y[0]).coerceAtLeast(1f)
    return a / b
}

private fun classify(p: HandPose?): Sign {
    if (p == null) return Sign.NONE
    val i = fingerExt(p, 8, 6)
    val m = fingerExt(p, 12, 10)
    val r = fingerExt(p, 16, 14)
    val k = fingerExt(p, 20, 18)
    return when {
        i > 1.30f && m > 1.30f && r > 1.25f && k > 1.25f -> Sign.PALM
        i > 1.30f && m > 1.30f && r < 1.15f && k < 1.15f -> Sign.PEACE
        else -> Sign.NONE
    }
}

private class SelfieHold {
    var holdStart = 0L
    var miss = 0
    var palmFrames = 0
    var cooldownUntil = 0L
}

/**
 * Gesture Selfie:
 * ✌️ Show peace sign for ~1 sec  -> 3-2-1 countdown -> photo auto-saved to Pictures/SmartClassifier
 * 🖐 Show open palm during countdown -> cancel
 */
@Composable
fun GestureSelfieScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Permission ───────────────────────────────────────────────────────────
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val perms = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { cameraGranted = granted(Manifest.permission.CAMERA) }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !asked) {
            asked = true
            permLauncher.launch(perms)
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
            Text("Camera permission is required for Gesture Selfie", fontWeight = FontWeight.Bold)
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

    // ── State ────────────────────────────────────────────────────────────────
    var isFront by rememberSaveable { mutableStateOf(true) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var handSeen by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var counting by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(3) }
    var flash by remember { mutableStateOf(false) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var modelLoading by remember { mutableStateOf(true) }
    var photoCount by remember { mutableIntStateOf(0) }

    val hold = remember { SelfieHold() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val tracker = remember { HandTracker(context.applicationContext) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(tracker) {
        val err = withContext(Dispatchers.IO) { tracker.prepare() }
        modelLoading = false
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) {
        onDispose {
            tracker.close()
            executor.shutdown()
        }
    }

    fun takePhoto() {
        val ic = imageCapture ?: return
        val name = "GestureSelfie_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SmartClassifier")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFront }).build()

        flash = true
        ic.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastUri = output.savedUri
                    photoCount++
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        context,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // Countdown 3-2-1 -> shoot (cancelled automatically when `counting` turns false)
    LaunchedEffect(counting) {
        if (counting) {
            for (i in 3 downTo 1) {
                count = i
                delay(1000)
            }
            counting = false
            hold.cooldownUntil = SystemClock.uptimeMillis() + 2500
            takePhoto()
        }
    }

    LaunchedEffect(flash) {
        if (flash) {
            delay(150)
            flash = false
        }
    }
    val flashAlpha by animateFloatAsState(if (flash) 0.85f else 0f, label = "flash")

    // ── Per-frame gesture logic (MAIN thread) ────────────────────────────────
    fun onFrame(pose: HandPose?) {
        handSeen = pose != null
        val sign = classify(pose)
        val now = SystemClock.uptimeMillis()

        if (counting) {
            if (sign == Sign.PALM) {
                hold.palmFrames++
                if (hold.palmFrames >= 4) {
                    counting = false
                    hold.palmFrames = 0
                    hold.cooldownUntil = now + 1500
                }
            } else hold.palmFrames = 0
            return
        }

        if (now < hold.cooldownUntil) {
            holdProgress = 0f
            hold.holdStart = 0L
            return
        }

        if (sign == Sign.PEACE) {
            hold.miss = 0
            if (hold.holdStart == 0L) hold.holdStart = now
            holdProgress = ((now - hold.holdStart) / 900f).coerceIn(0f, 1f)
            if (holdProgress >= 1f) {
                hold.holdStart = 0L
                holdProgress = 0f
                count = 3
                counting = true
            }
        } else {
            hold.miss++
            if (hold.miss >= 3) {
                hold.holdStart = 0L
                holdProgress = 0f
            }
        }
    }

    val onFrameState = rememberUpdatedState<(HandPose?) -> Unit>(newValue = { onFrame(it) })

    // ── Bind CameraX (Preview + Analysis + Capture) ──────────────────────────
    DisposableEffect(lifecycleOwner, isFront) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            val p = future.get()
            provider = p
            p.unbindAll()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

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
                val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            imageCapture = null
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Box(Modifier
        .fillMaxSize()
        .background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top: status + flip
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
                Text(
                    text = when {
                        modelLoading -> "⏳ Loading hand model..."
                        counting -> "📸 Get ready... (🖐 palm = cancel)"
                        !handSeen -> "❓ Show your hand to camera"
                        holdProgress > 0f -> "✌️ Hold it..."
                        else -> "Show ✌️ peace sign to take a photo"
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            IconButton(
                onClick = { isFront = !isFront },
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Flip", tint = Color.White)
            }
        }

        // Big countdown number
        if (counting) {
            Text(
                text = count.toString(),
                color = Color.White,
                fontSize = 140.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Bottom: last photo thumbnail + hold progress
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (holdProgress > 0f) {
                LinearProgressIndicator(
                    progress = { holdProgress },
                    modifier = Modifier.fillMaxWidth(0.7f),
                    color = Color(0xFFFF9800)
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lastUri != null) {
                    AsyncImage(
                        model = lastUri,
                        contentDescription = "Last photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(lastUri, "image/jpeg")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                )
                            }
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    text = if (photoCount == 0) "✌️ Hold 1 sec → 3-2-1 → 📸"
                    else "📷 $photoCount saved in Pictures/SmartClassifier",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }

        // White flash on capture
        if (flashAlpha > 0f) {
            Box(Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha)))
        }
    }
}