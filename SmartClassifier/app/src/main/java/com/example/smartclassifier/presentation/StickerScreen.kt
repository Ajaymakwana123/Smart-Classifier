package com.example.smartclassifier.presentation

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.smartclassifier.presentation.util.rememberImageSourcePicker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private class FitInfo(val scale: Float, val offset: Offset)

@Composable
fun StickerScreen(viewModel: StickerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.messageShown()
        }
    }

    // Single button -> Camera / Gallery
    val openPicker = rememberImageSourcePicker { viewModel.onImageSelected(it) }

    val source = state.source
    val sticker = state.sticker
    var box by remember { mutableStateOf(IntSize.Zero) }

    // Where the (Fit-scaled) photo sits inside the container
    val fit = remember(source, box) {
        if (source == null || box == IntSize.Zero) null
        else {
            val s = min(box.width / source.width.toFloat(), box.height / source.height.toFloat())
            FitInfo(s, Offset((box.width - source.width * s) / 2f, (box.height - source.height * s) / 2f))
        }
    }

    // Sticker transform — starts exactly over the original subject (like "lifting" it)
    var center by remember(sticker, fit) {
        mutableStateOf(
            if (sticker != null && fit != null) {
                fit.offset + Offset(
                    (sticker.left + sticker.bitmap.width / 2f) * fit.scale,
                    (sticker.top + sticker.bitmap.height / 2f) * fit.scale
                )
            } else Offset.Zero
        )
    }
    val userScale = remember(sticker, fit) { mutableFloatStateOf(1f) }
    val rotation = remember(sticker, fit) { mutableFloatStateOf(0f) }

    fun hitSticker(p: Offset): Boolean {
        val st = sticker ?: return false
        val f = fit ?: return false
        val w = st.bitmap.width * f.scale * userScale.floatValue
        val h = st.bitmap.height * f.scale * userScale.floatValue
        val d = p - center
        val rad = Math.toRadians(rotation.floatValue.toDouble())
        val x = d.x * cos(rad) + d.y * sin(rad)
        val y = -d.x * sin(rad) + d.y * cos(rad)
        return abs(x) <= w / 2.0 && abs(y) <= h / 2.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = openPicker, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (source == null) "Take / Pick Photo" else "Change Photo")
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { box = it }
                .pointerInput(sticker, fit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        if (hitSticker(down.position)) {
                            // Drag / pinch-zoom / rotate the sticker
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.positionChanged() }) {
                                    userScale.floatValue =
                                        (userScale.floatValue * event.calculateZoom()).coerceIn(0.3f, 5f)
                                    rotation.floatValue += event.calculateRotation()
                                    center += event.calculatePan()
                                    event.changes.forEach { c: PointerInputChange ->
                                        if (c.positionChanged()) c.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        } else {
                            // Long-press on the photo -> lift the subject as a sticker
                            val longPress = awaitLongPressOrCancellation(down.id)
                            val f = fit
                            val src = source
                            if (longPress != null && f != null && src != null) {
                                val px = ((down.position.x - f.offset.x) / f.scale).roundToInt()
                                val py = ((down.position.y - f.offset.y) / f.scale).roundToInt()
                                if (px in 0 until src.width && py in 0 until src.height) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.liftSubject(px, py)
                                }
                                waitForUpOrCancellation()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopStart
        ) {
            if (source == null) {
                Text(
                    "Add a photo, then long-press on a person or object to turn it into a sticker.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                val photo = remember(source) { source.asImageBitmap() }
                Image(
                    bitmap = photo,
                    contentDescription = "Photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            if (sticker != null && fit != null) {
                val stickerImage = remember(sticker) { sticker.bitmap.asImageBitmap() }
                val wPx = sticker.bitmap.width * fit.scale * userScale.floatValue
                val hPx = sticker.bitmap.height * fit.scale * userScale.floatValue
                Image(
                    bitmap = stickerImage,
                    contentDescription = "Sticker",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset { IntOffset((center.x - wPx / 2f).roundToInt(), (center.y - hPx / 2f).roundToInt()) }
                        .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        .graphicsLayer { rotationZ = rotation.floatValue }
                )
            }

            if (state.isBusy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        Spacer(Modifier.height(8.dp))

        if (sticker != null) {
            Text(
                "Drag to move • Pinch to resize / rotate • Long-press another object to replace",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = viewModel::clearSticker) { Text("Remove sticker") }
        } else if (source != null) {
            Text("Long-press on a person or object", style = MaterialTheme.typography.bodySmall)
        }
    }
}
