package com.example.smartclassifier.presentation.ar

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.hypot

class HandPose(
    val x: FloatArray,
    val y: FloatArray,
    val imgW: Float,
    val imgH: Float
) {
    private fun d(a: Int, b: Int) = hypot(x[a] - x[b], y[a] - y[b])

    /** wrist -> middle finger MCP : used to normalise every distance */
    val handSize: Float get() = d(0, 9).coerceAtLeast(1f)

    /** thumb tip <-> index tip, relative to hand size (≈0.1 pinched, ≈1.0+ open) */
    val pinchRatio: Float get() = d(4, 8) / handSize

    /** tip-to-wrist / pip-to-wrist. >1.3 = finger straight, <1.1 = finger curled */
    private fun ext(tip: Int, pip: Int) = d(tip, 0) / d(pip, 0).coerceAtLeast(1f)

    val indexExt: Float get() = ext(8, 6)
    val maxOtherExt: Float get() = maxOf(ext(12, 10), ext(16, 14), ext(20, 18))

    /** Stable pen position while pinching = middle of thumb tip & index tip */
    val pinchCenterX: Float get() = (x[4] + x[8]) / 2f
    val pinchCenterY: Float get() = (y[4] + y[8]) / 2f
}

/**
 * MediaPipe Hand Landmarker (21 real hand landmarks).
 * Model (~7.5 MB) is downloaded once into filesDir on first launch.
 */
class HandTracker(private val context: Context) {

    private var landmarker: HandLandmarker? = null
    private var lastTs = 0L

    @Volatile
    var ready = false
        private set

    /** Blocking – call from Dispatchers.IO. Returns error text or null on success. */
    fun prepare(): String? {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists() || file.length() < 1_000_000L) {
                val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
                URL(MODEL_URL).openStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                tmp.renameTo(file)
            }
            val bytes = file.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                rewind()
            }
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            synchronized(this) {
                landmarker = HandLandmarker.createFromOptions(context, options)
            }
            ready = true
            null
        } catch (e: Exception) {
            "Hand model load failed: ${e.message}. Check internet once."
        }
    }

    /** [bitmap] must be upright (already rotated) ARGB_8888. */
    @Synchronized
    fun detect(bitmap: Bitmap, timestampMs: Long): HandPose? {
        val lm = landmarker ?: return null
        val ts = if (timestampMs <= lastTs) lastTs + 1 else timestampMs
        lastTs = ts
        val result = lm.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val hand = result.landmarks().firstOrNull() ?: return null
        if (hand.size < 21) return null
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return HandPose(
            x = FloatArray(21) { hand[it].x() * w },
            y = FloatArray(21) { hand[it].y() * h },
            imgW = w,
            imgH = h
        )
    }

    @Synchronized
    fun close() {
        ready = false
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    }
}