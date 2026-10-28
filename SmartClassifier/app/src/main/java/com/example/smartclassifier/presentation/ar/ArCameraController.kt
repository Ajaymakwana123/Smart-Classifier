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
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import com.example.smartclassifier.domain.model.FaceAnchor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.hypot

class ArCameraController(
    private val context: Context,
    private val board: DoodleBoard,
    private val onElapsed: (Long) -> Unit,
    private val onRecordingFinished: (Boolean, String) -> Unit,
    private val onError: (String) -> Unit
) {

    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlayEffect: OverlayEffect? = null
    private var recording: Recording? = null
    private var released = false
    private val orientation = OrientationTracker(context).also {
        board.orientation = it
        it.start()
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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
        board.resetFace()
        board.projection = CameraProjection.create(context, front)
        if (!orientation.isAvailable) {
            onError("Motion sensor not available, doodles won't stay in place")
        }
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder().setTargetRotation(rotation).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val recorder = Recorder.Builder().setQualitySelector(
            QualitySelector.from(
                Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
        ).build()
        val video = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        videoCapture = video

        val analysis =
            ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation).build()
                .also { a -> a.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) } }

        val effect = createEffect()
        overlayEffect = effect

        val selector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        fun groupOf(withAnalysis: Boolean) =
            UseCaseGroup.Builder().addUseCase(preview).addUseCase(video)
                .apply { if (withAnalysis) addUseCase(analysis) }.addEffect(effect).build()

        try {
            provider.bindToLifecycle(owner, selector, groupOf(true))
        } catch (e: Exception) {
            try {
                // Some devices can't run preview + video + analysis together -> drop face tracking
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, groupOf(false))
                onError("Face tracking is not supported on this device")
            } catch (e2: Exception) {
                onError(e2.message ?: "Unable to start camera")
            }
        }
    }

    private fun createEffect(): OverlayEffect {
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, Handler(Looper.getMainLooper())
        ) { t -> Log.e(TAG, "Overlay effect error", t) }

        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            board.draw(canvas, frame.size.width, frame.size.height, frame.sensorToBufferTransform)
            true
        }
        return effect
    }

    // ------------------------------------------------------------ touch -> sensor

    fun viewToSensor(x: Float, y: Float): PointF? {
        val sensorToView = previewView.sensorToViewTransform ?: return null
        val inverse = Matrix()
        if (!sensorToView.invert(inverse)) return null
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    // ------------------------------------------------------------ face tracking

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
                .addOnSuccessListener { faces -> updateFace(faces, rotation, w, h, bufferToSensor) }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            proxy.close()
        }
    }

    private fun updateFace(
        faces: List<Face>, rotation: Int, w: Int, h: Int, bufferToSensor: Matrix
    ) {
        val face = faces.maxByOrNull { it.boundingBox.width() } ?: return
        val box = face.boundingBox
        val center =
            uprightToSensor(box.exactCenterX(), box.exactCenterY(), rotation, w, h, bufferToSensor)
        val edge = uprightToSensor(
            box.exactCenterX() + box.width() / 2f,
            box.exactCenterY(),
            rotation,
            w,
            h,
            bufferToSensor
        )
        val size = 2f * hypot(edge.x - center.x, edge.y - center.y)
        if (size <= 0f) return

        val prev = board.face
        val a = if (prev == null) 1f else 0.6f   // smoothing
        board.face = FaceAnchor(
            cx = lerp(prev?.cx, center.x, a),
            cy = lerp(prev?.cy, center.y, a),
            size = lerp(prev?.size, size, a),
            angleDeg = lerp(prev?.angleDeg, face.headEulerAngleZ, a)
        )
    }

    private fun lerp(old: Float?, new: Float, a: Float): Float =
        if (old == null) new else old + (new - old) * a

    /** Point in the upright (rotated) analysis image -> camera sensor coordinates. */
    private fun uprightToSensor(
        x: Float, y: Float, rotation: Int, w: Int, h: Int, bufferToSensor: Matrix
    ): PointF {
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

    // ------------------------------------------------------------ recording

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val video = videoCapture ?: return
        if (recording != null) return

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ARDoodle_${System.currentTimeMillis()}.mp4")
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
                is VideoRecordEvent.Status -> onElapsed(event.recordingStats.recordedDurationNanos / 1_000_000L)

                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (event.hasError()) {
                        onRecordingFinished(false, "Recording failed (code ${event.error})")
                    } else {
                        onRecordingFinished(true, "Video saved to Movies/SmartClassifier")
                    }
                }

                else -> Unit
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    // ------------------------------------------------------------ cleanup

    fun release() {
        released = true
        orientation.stop()
        recording?.stop()
        cameraProvider?.unbindAll()
        overlayEffect?.close()
        overlayEffect = null
        faceDetector.close()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "ArCameraController"
    }
}
