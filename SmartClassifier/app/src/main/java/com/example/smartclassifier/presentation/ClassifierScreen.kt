package com.example.smartclassifier.presentation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.smartclassifier.domain.model.FaceInfo
import java.io.File

@Composable
fun ClassifierScreen(viewModel: ClassifierViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    var cameraImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }

    // One-shot messages -> Toast
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }

    // Gallery (system Photo Picker — no storage permission needed)
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it) } }

    // Second photo (face source for swap)
    val swapPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.swapWithPhoto(it) } }

    // Camera capture
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) cameraImageUri?.let { viewModel.onImageSelected(it) }
    }

    fun launchCamera() {
        try {
            val uri = createImageUri(context)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Camera app not found", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open camera", Toast.LENGTH_SHORT).show()
        }
    }

    // Runtime permission: CAMERA
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) launchCamera() else showPermissionDialog = true
    }

    fun onCameraClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Runtime permission: WRITE_EXTERNAL_STORAGE (only Android 9 and below, for Save)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) viewModel.saveImage()
        else viewModel.postMessage("Storage permission is required to save the image")
    }

    fun onSaveClick() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.saveImage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---------------- Image (tap to pick)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clickable(enabled = !state.isBusy) { showSourceDialog = true },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bitmap = state.displayBitmap
                if (bitmap != null) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Selected image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Tap to select image")
                }
                if (state.isBusy) CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showSourceDialog = true },
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f)
            ) { Text(if (state.baseBitmap == null) "Pick Image" else "Change") }

            OutlinedButton(
                onClick = viewModel::undo,
                enabled = state.canUndo && !state.isBusy,
                modifier = Modifier.weight(1f)
            ) { Text("Undo") }

            OutlinedButton(
                onClick = { onSaveClick() },
                enabled = state.baseBitmap != null && !state.isBusy,
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }

        Spacer(Modifier.height(16.dp))

        // ---------------- Faces
        if (state.baseBitmap != null) {
            SectionTitle("Faces detected: ${state.faces.size}")
            if (state.faces.isEmpty()) {
                Text("No face found in this image")
            } else {
                state.faces.forEach { face ->
                    FaceCard(
                        face = face,
                        selected = face.id in state.selectedFaceIds,
                        onToggle = { viewModel.toggleFace(face.id) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---------------- Edit: face swap
                SectionTitle("Edit — Face Swap")
                Text(
                    "Select 2 faces to swap them, or select 1 face and use a face from another photo.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::swapSelectedFaces,
                    enabled = state.selectedFaceIds.size == 2 && !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Swap selected faces") }
                OutlinedButton(
                    onClick = {
                        swapPhotoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = state.selectedFaceIds.size == 1 && !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use face from another photo") }
            }

            Spacer(Modifier.height(16.dp))
        }

        // ---------------- Labels
        when (val labels = state.labels) {
            is ClassifierUiState.Idle -> Text("Select an image to classify")
            is ClassifierUiState.Loading -> Unit
            is ClassifierUiState.Error -> Text("Error: ${labels.message}")
            is ClassifierUiState.Success -> {
                SectionTitle("Image labels")
                if (labels.results.isEmpty()) {
                    Text("No labels found")
                } else {
                    labels.results.forEach { result ->
                        Text(
                            "${result.label} — ${(result.confidence * 100).toInt()}%",
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("Select image from") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showSourceDialog = false
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Gallery") }
                    TextButton(
                        onClick = {
                            showSourceDialog = false
                            onCameraClick()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Camera") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Camera permission required") },
            text = { Text("Allow camera permission to capture a photo. You can enable it from app settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

@Composable
private fun FaceCard(face: FaceInfo, selected: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onToggle() },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text("Face ${face.id}", fontWeight = FontWeight.Bold)
                val smile = face.smileProbability?.let { " (smile ${(it * 100).toInt()}%)" } ?: ""
                Text("Mood: ${face.mood.emoji} ${face.mood.label}$smile")
                Text("Pose: ${face.pose.emoji} ${face.pose.label}")
            }
        }
    }
}

private fun createImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("capture_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
