package com.example.smartclassifier.presentation.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.smartclassifier.domain.model.EmojiFace
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Preview + photo + video + face analysis. The [OverlayEffect] draws the avatar on preview,
 * photo and recorded video, so what you see is exactly what gets saved.
 */
class EmojiCameraController(
    private val context: Context,
    private val renderer: EmojiAvatarRenderer,
    private val onElapsed: (Long) -> Unit,
    private val onFaceVisible: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit
) {

    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var overlayEffect: OverlayEffect? = null
    private var recording: Recording? = null
    private var released = false
    private var faceVisible = false
    private var previous: EmojiFace? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    // ------------------------------------------------------------ binding

    fun bind(owner: LifecycleOwner, front: Boolean) {
        val provider = cameraProvider
        if (provider != null) {
            doBind(provider, owner, front)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            val p = future.get()
            cameraProvider = p
            doBind(p, owner, front)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun doBind(provider: ProcessCameraProvider, owner: LifecycleOwner, front: Boolean) {
        if (released) return
        provider.unbindAll()
        overlayEffect?.close()
        overlayEffect = null
        renderer.face = null
        previous = null

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().setTargetRotation(rotation).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val video = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        videoCapture = video

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
            .also { a -> a.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }

        fun attempt(withPhoto: Boolean) {
            val photo = if (withPhoto) {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation)
                    .build()
            } else null
            val effect = createEffect(withPhoto)
            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(video)
                .addUseCase(analysis)
                .apply { if (photo != null) addUseCase(photo) }
                .addEffect(effect)
                .build()
            try {
                provider.bindToLifecycle(owner, selector, group)
                imageCapture = photo
                overlayEffect = effect
            } catch (e: Exception) {
                effect.close()
                throw e
            }
        }

        try {
            attempt(true)
        } catch (e: Exception) {
            try {
                provider.unbindAll()
                imageCapture = null
                attempt(false)
                onMessage("Photo capture not supported on this device (video works)")
            } catch (e2: Exception) {
                onMessage(e2.message ?: "Unable to start camera")
            }
        }
    }

    private fun createEffect(withPhoto: Boolean): OverlayEffect {
        var targets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
        if (withPhoto) targets = targets or CameraEffect.IMAGE_CAPTURE

        val effect = OverlayEffect(targets, 0, Handler(Looper.getMainLooper())) { t ->
            Log.e(TAG, "Overlay effect error", t)
        }
        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            renderer.draw(canvas, frame.size.width, frame.size.height, frame.sensorToBufferTransform)
            true
        }
        return effect
    }

    // ------------------------------------------------------------ face analysis

    @Suppress("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || released) {
            proxy.close()
            return
        }
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val bufferToSensor = Matrix()
            if (!Matrix(proxy.imageInfo.sensorToBufferTransformMatrix).invert(bufferToSensor)) {
                proxy.close()
                return
            }
            val w = proxy.width
            val h = proxy.height
            faceDetector.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.width() }
                    if (face == null) {
                        setFaceVisible(false)
                    } else {
                        setFaceVisible(true)
                        val upToSensor = uprightToSensorMatrix(rotation, w, h, bufferToSensor)
                        val built = buildFace(face, upToSensor)
                        val smoothed = smooth(previous, built)
                        previous = smoothed
                        renderer.face = smoothed
                    }
                }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            proxy.close()
        }
    }

    private fun setFaceVisible(v: Boolean) {
        if (v != faceVisible) {
            faceVisible = v
            onFaceVisible(v)
        }
    }

    private fun buildFace(face: Face, upToSensor: Matrix): EmojiFace {
        val box = face.boundingBox
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()

        fun landmark(type: Int): PointF? = face.getLandmark(type)?.position
        fun contour(type: Int): List<PointF> = face.getContour(type)?.points ?: emptyList()
        fun centroid(pts: List<PointF>): PointF? =
            if (pts.isEmpty()) null
            else PointF(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())

        // Eyes + brows (ML Kit left/right -> sorted by x into image-left / image-right)
        val eyeA = landmark(FaceLandmark.LEFT_EYE) ?: PointF(cx + w * 0.22f, cy - h * 0.12f)
        val eyeB = landmark(FaceLandmark.RIGHT_EYE) ?: PointF(cx - w * 0.22f, cy - h * 0.12f)
        val openA: Float = face.leftEyeOpenProbability ?: 1f
        val openB: Float = face.rightEyeOpenProbability ?: 1f
        val browA = centroid(contour(FaceContour.LEFT_EYEBROW_TOP)) ?: PointF(eyeA.x, eyeA.y - h * 0.12f)
        val browB = centroid(contour(FaceContour.RIGHT_EYEBROW_TOP)) ?: PointF(eyeB.x, eyeB.y - h * 0.12f)

        val aIsLeft = eyeA.x <= eyeB.x
        val eyeL = if (aIsLeft) eyeA else eyeB
        val eyeR = if (aIsLeft) eyeB else eyeA
        val openL = if (aIsLeft) openA else openB
        val openR = if (aIsLeft) openB else openA
        val browL = if (aIsLeft) browA else browB
        val browR = if (aIsLeft) browB else browA

        // Mouth
        val upperInner = centroid(contour(FaceContour.UPPER_LIP_BOTTOM))
        val lowerInner = centroid(contour(FaceContour.LOWER_LIP_TOP))
        val allLips = contour(FaceContour.UPPER_LIP_TOP) + contour(FaceContour.UPPER_LIP_BOTTOM) +
                contour(FaceContour.LOWER_LIP_TOP) + contour(FaceContour.LOWER_LIP_BOTTOM)
        val mouth = centroid(allLips) ?: PointF(cx, cy + h * 0.28f)

        val mLeft = landmark(FaceLandmark.MOUTH_LEFT)
        val mRight = landmark(FaceLandmark.MOUTH_RIGHT)
        val mouthWidth = if (mLeft != null && mRight != null) {
            hypot(mLeft.x - mRight.x, mLeft.y - mRight.y)
        } else w * 0.4f

        val rawOpen = if (upperInner != null && lowerInner != null) {
            hypot(upperInner.x - lowerInner.x, upperInner.y - lowerInner.y) / h
        } else 0f
        val mouthOpen = ((rawOpen - 0.012f) / 0.09f).coerceIn(0f, 1f)

        val smile: Float = face.smilingProbability ?: 0.3f
        val nose = landmark(FaceLandmark.NOSE_BASE) ?: PointF(cx, cy + h * 0.10f)

        return EmojiFace(
            uprightToSensor = upToSensor,
            cx = cx, cy = cy, width = w, height = h,
            rollDeg = face.headEulerAngleZ,
            eyeLeft = eyeL, eyeRight = eyeR,
            openLeft = openL.coerceIn(0f, 1f), openRight = openR.coerceIn(0f, 1f),
            browLeft = browL, browRight = browR,
            nose = nose, mouth = mouth, mouthWidth = mouthWidth,
            mouthOpen = mouthOpen, smile = smile.coerceIn(0f, 1f),
            timeMs = SystemClock.uptimeMillis()
        )
    }

    /** Light smoothing (blink / mouth react faster than position). */
    private fun smooth(prev: EmojiFace?, n: EmojiFace): EmojiFace {
        if (prev == null) return n
        fun l(o: Float, v: Float, a: Float = 0.6f) = o + (v - o) * a
        fun p(o: PointF, v: PointF) = PointF(l(o.x, v.x), l(o.y, v.y))
        return EmojiFace(
            uprightToSensor = n.uprightToSensor,
            cx = l(prev.cx, n.cx), cy = l(prev.cy, n.cy),
            width = l(prev.width, n.width), height = l(prev.height, n.height),
            rollDeg = l(prev.rollDeg, n.rollDeg),
            eyeLeft = p(prev.eyeLeft, n.eyeLeft), eyeRight = p(prev.eyeRight, n.eyeRight),
            openLeft = l(prev.openLeft, n.openLeft, 0.85f), openRight = l(prev.openRight, n.openRight, 0.85f),
            browLeft = p(prev.browLeft, n.browLeft), browRight = p(prev.browRight, n.browRight),
            nose = p(prev.nose, n.nose), mouth = p(prev.mouth, n.mouth),
            mouthWidth = l(prev.mouthWidth, n.mouthWidth),
            mouthOpen = l(prev.mouthOpen, n.mouthOpen, 0.8f),
            smile = l(prev.smile, n.smile),
            timeMs = n.timeMs
        )
    }

    /** Affine matrix: upright analysis-image coords -> camera sensor coords. */
    private fun uprightToSensorMatrix(rotation: Int, w: Int, h: Int, bufferToSensor: Matrix): Matrix {
        fun map(x: Float, y: Float): PointF {
            val (bx, by) = when (rotation) {
                90 -> y to (h - x)
                180 -> (w - x) to (h - y)
                270 -> (w - y) to x
                else -> x to y
            }
            val pts = floatArrayOf(bx, by)
            bufferToSensor.mapPoints(pts)
            return PointF(pts[0], pts[1])
        }
        val o = map(0f, 0f)
        val ex = map(1f, 0f)
        val ey = map(0f, 1f)
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), 0,
            floatArrayOf(o.x, o.y, ex.x, ex.y, ey.x, ey.y), 0,
            3
        )
        return m
    }

    // ------------------------------------------------------------ photo

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            onMessage("Photo capture not available")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AREmoji_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartClassifier")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onMessage("Photo saved to Pictures/SmartClassifier")
                }

                override fun onError(exception: ImageCaptureException) {
                    onMessage("Photo failed: ${exception.message}")
                }
            }
        )
    }

    // ------------------------------------------------------------ video

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val video = videoCapture ?: return
        if (recording != null) return

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AREmoji_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SmartClassifier")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = video.output.prepareRecording(context, options)
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status ->
                    onElapsed(event.recordingStats.recordedDurationNanos / 1_000_000L)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    onElapsed(-1L)   // -1 = recording ended
                    onMessage(
                        if (event.hasError()) "Recording failed (code ${event.error})"
                        else "Video saved to Movies/SmartClassifier"
                    )
                }
                else -> Unit
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }


    fun release() {
        released = true
        recording?.stop()
        cameraProvider?.unbindAll()
        overlayEffect?.close()
        overlayEffect = null
        faceDetector.close()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "EmojiCameraController"
    }
}