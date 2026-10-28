package com.example.smartclassifier.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.smartclassifier.domain.model.EmojiStyle
import com.example.smartclassifier.presentation.ar.EmojiCameraController

private fun emojiPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

@Composable
fun EmojiScreen(viewModel: EmojiViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------- Permissions
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { cameraGranted = granted(Manifest.permission.CAMERA) }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(emojiPermissions())
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
            Text("Camera permission is required for AR Emoji")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(emojiPermissions()) }) { Text("Grant permission") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
            }) { Text("Open Settings") }
        }
        return
    }

    // ---------------- Camera
    val controller = remember {
        EmojiCameraController(
            context = context,
            renderer = viewModel.renderer,
            onElapsed = viewModel::onElapsed,
            onFaceVisible = viewModel::onFaceVisible,
            onMessage = viewModel::postMessage
        )
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(state.useFrontCamera) { controller.bind(lifecycleOwner, state.useFrontCamera) }

    fun storageOk(): Boolean {
        val needs =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (needs) {
            permissionLauncher.launch(emojiPermissions())
            viewModel.postMessage("Allow storage permission, then try again")
        }
        return !needs
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(factory = { controller.previewView }, modifier = Modifier.fillMaxSize())

        // Top: status
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            val text = when {
                state.isRecording -> "● REC  ${formatTime(state.elapsedMs)}"
                !state.faceVisible -> "Show your face to the camera"
                else -> "AR Emoji: ${state.style.label}"
            }
            Text(
                text,
                color = if (state.isRecording) Color(0xFFFF5252) else Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Character picker
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EmojiStyle.values().forEach { style ->
                    val selected = style == state.style
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape)
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) Color(0xFFFF9800) else Color.Gray,
                                CircleShape
                            )
                            .clickable { viewModel.setStyle(style) },
                        contentAlignment = Alignment.Center
                    ) { Text(style.icon, fontSize = 26.sp) }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Photo / Video mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { viewModel.setVideoMode(false) }, enabled = !state.isRecording
                ) {
                    Text("PHOTO", color = if (!state.videoMode) Color(0xFFFF9800) else Color.White)
                }
                TextButton(
                    onClick = { viewModel.setVideoMode(true) }, enabled = !state.isRecording
                ) {
                    Text("VIDEO", color = if (state.videoMode) Color(0xFFFF9800) else Color.White)
                }
            }

            // Flip | Shutter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = viewModel::flipCamera, enabled = !state.isRecording
                ) { Text("Flip") }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(
                            if (state.videoMode) Color(0xFFFF1744) else Color.White,
                            if (state.isRecording) RoundedCornerShape(10.dp) else CircleShape
                        )
                        .clickable {
                            if (!state.videoMode) {
                                if (storageOk()) controller.takePhoto()
                            } else if (state.isRecording) {
                                controller.stopRecording()
                            } else if (storageOk()) {
                                controller.startRecording()
                                viewModel.onRecordingStarted()
                            }
                        })

                Spacer(Modifier.width(64.dp))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}