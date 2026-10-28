package com.example.smartclassifier.presentation.ar

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.google.ar.core.Anchor
import kotlin.math.sin
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.File
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

/** One doodle line, stored relative to its own ARCore anchor so it stays fixed in the real world. */
private class ArStroke(
    val anchor: Anchor,
    val color: FloatArray,
    val halfWidth: Float,
    val lights: Boolean = false,
    val multicolor: Boolean = false
) {
    var local = FloatArray(192)
    var count = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    fun add(x: Float, y: Float, z: Float) {
        if (count * 3 + 3 > local.size) local = local.copyOf(local.size * 2)
        local[count * 3] = x
        local[count * 3 + 1] = y
        local[count * 3 + 2] = z
        count++
        lastX = x; lastY = y; lastZ = z
    }

    fun distanceSqTo(x: Float, y: Float, z: Float): Float {
        val dx = x - lastX
        val dy = y - lastY
        val dz = z - lastZ
        return dx * dx + dy * dy + dz * dz
    }
}

/** One pinned 3D text label. */
private class ArLabel(val anchor: Anchor, val instance: LabelInstance)

/**
 * ARCore AR Doodle (Samsung / Pixel style): strokes are anchored in 3D world space with full
 * 6-DoF tracking, so a doodle stays on the real object while you walk around it.
 * Recording renders the same scene into a MediaRecorder surface (camera + doodles in the video).
 */
class ArDoodleController(
    private val activity: Activity,
    private val onTracking: (Boolean) -> Unit,
    private val onStrokeCount: (Int) -> Unit,
    private val onElapsed: (Long) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit
) : GLSurfaceView.Renderer {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session: Session? = null
    private var installRequested = false

    private val background = BackgroundRenderer()
    private val strokeRenderer = StrokeRenderer()
    private val labelRenderer = LabelRenderer()

    // `private val labelRenderer = LabelRenderer()` ke neeche:
    private val lightRenderer = LightRenderer()

    // `@Volatile var brush: Float = 0.3f` ke neeche:
    @Volatile
    var lightsMode: Boolean = false
    @Volatile
    var lightPattern: LightPattern = LightPattern.CHASE

    // `private val camRight = FloatArray(3)` ke neeche:
    private val camUp = FloatArray(3)
    private var animTime = 0f
    private val recorder = ArVideoRecorder(activity.applicationContext)
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Written on UI thread, read on GL thread
    @Volatile
    var colorArgb: Int = Color.WHITE

    @Volatile
    var brush: Float = 0.3f

    // GL-thread only
    private val strokes = ArrayList<ArStroke>()
    private var current: ArStroke? = null
    private val labels = ArrayList<ArLabel>()
    private val labelInstances = ArrayList<LabelInstance>()
    private val history = ArrayList<Any>()          // strokes + labels in creation order (for undo)
    private val planePoint = FloatArray(3)
    private val planeNormal = FloatArray(3)
    private var lastFrame: Frame? = null
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val anchorMatrix = FloatArray(16)
    private val camPos = FloatArray(3)
    private val camRight = FloatArray(3)
    private var worldBuf = FloatArray(768)
    private var viewW = 0
    private var viewH = 0
    private var displayChanged = true
    private var textureAttached = false
    private var trackingNow = false
    private var recStartMs = 0L
    private var lastElapsedPost = 0L

    @Volatile
    private var stopQueued = false

    val glView: GLSurfaceView = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(RecordableConfigChooser())
        setRenderer(this@ArDoodleController)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    // ============================================================ lifecycle

    /** @return error text to show to the user, or null if AR started fine. */
    fun resume(): String? {
        if (session == null) {
            try {
                val status = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return "Installing Google Play Services for AR…"
                }
                val s = Session(activity)
                val config = Config(s)
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.focusMode = Config.FocusMode.AUTO
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                }
                s.configure(config)
                session = s
            } catch (e: UnavailableUserDeclinedInstallationException) {
                return "Google Play Services for AR is required for AR Doodle"
            } catch (e: UnavailableDeviceNotCompatibleException) {
                return "This device does not support ARCore"
            } catch (e: UnavailableApkTooOldException) {
                return "Please update Google Play Services for AR"
            } catch (e: UnavailableSdkTooOldException) {
                return "Please update this app"
            } catch (e: UnavailableArcoreNotInstalledException) {
                return "Google Play Services for AR is not installed"
            } catch (e: Exception) {
                return "AR is not available: ${e.message}"
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            return "Camera is not available. Close other apps using the camera."
        } catch (e: Exception) {
            session = null
            return "Unable to start AR: ${e.message}"
        }
        displayChanged = true
        glView.onResume()
        return null
    }

    fun pause() {
        if (recorder.isActive) stopRecording()
        glView.onPause()
        session?.pause()
    }

    fun release() {
        if (recorder.isActive) stopRecording()
        session?.close()
        session = null
        ioExecutor.shutdown()
    }

    // ============================================================ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.init()
            strokeRenderer.init()
            labelRenderer.init()
            lightRenderer.init()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
        }
        textureAttached = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
        displayChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewW == 0 || viewH == 0) return

        try {
            if (!textureAttached) {
                s.setCameraTextureName(background.textureId)
                textureAttached = true
            }
            if (displayChanged) {
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                s.setDisplayGeometry(rotation, viewW, viewH)
                displayChanged = false
            }

            val frame = s.update()
            val camera = frame.camera
            lastFrame = frame

            val tracking = camera.trackingState == TrackingState.TRACKING
            if (tracking != trackingNow) {
                trackingNow = tracking
                main.post { onTracking(tracking) }
            }

            strokeRenderer.beginFrame()
            lightRenderer.beginFrame()

            if (tracking) {
                camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
                camera.getViewMatrix(view, 0)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

                val t = camera.displayOrientedPose.translation
                camPos[0] = t[0]; camPos[1] = t[1]; camPos[2] = t[2]
                camRight[0] = view[0]; camRight[1] = view[4]; camRight[2] = view[8]
                camUp[0] = view[1]; camUp[1] = view[5]; camUp[2] = view[9]
                animTime = (SystemClock.uptimeMillis() % 60000L) / 1000f
                for (stroke in strokes) addToRenderer(stroke)
                current?.let { addToRenderer(it) }
                for (label in labels) {
                    val ok = label.anchor.trackingState == TrackingState.TRACKING
                    label.instance.visible = ok
                    if (ok) label.anchor.pose.toMatrix(label.instance.model, 0)
                }
            }

            // Step 1: draw on screen
            drawScene(frame, tracking)

            // Step 2: draw into the video
            if (recorder.isActive) {
                recorder.render(System.nanoTime()) { w, h ->
                    GLES20.glViewport(0, 0, w, h)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                    drawScene(frame, tracking)
                }
                GLES20.glViewport(0, 0, viewW, viewH)

                val now = SystemClock.elapsedRealtime()
                if (now - lastElapsedPost > 500) {
                    lastElapsedPost = now
                    val elapsed = now - recStartMs
                    main.post { onElapsed(elapsed) }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Frame error", e)
        }
    }

    private fun drawScene(frame: Frame, tracking: Boolean) {
        background.draw(frame)
        if (tracking) {
            strokeRenderer.draw(vp)
            lightRenderer.draw(vp)
            labelRenderer.draw(labelInstances, vp, camPos)
        }
    }

    private fun addToRenderer(stroke: ArStroke) {
        val n = stroke.count
        if (n < 2) return
        if (stroke.anchor.trackingState != TrackingState.TRACKING) return
        if (worldBuf.size < n * 3) worldBuf = FloatArray(n * 3 * 2)

        stroke.anchor.pose.toMatrix(anchorMatrix, 0)
        val m = anchorMatrix
        val l = stroke.local
        for (i in 0 until n) {
            val x = l[3 * i]
            val y = l[3 * i + 1]
            val z = l[3 * i + 2]
            worldBuf[3 * i] = m[0] * x + m[4] * y + m[8] * z + m[12]
            worldBuf[3 * i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            worldBuf[3 * i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        }
        if (stroke.lights) {
            strokeRenderer.addStroke(
                worldBuf,
                n,
                stroke.halfWidth * WIRE_RATIO,
                WIRE_COLOR,
                camPos,
                camRight
            )
            addBulbs(stroke, n)
        } else {
            strokeRenderer.addStroke(worldBuf, n, stroke.halfWidth, stroke.color, camPos, camRight)
        }
    }

    private fun addBulbs(stroke: ArStroke, n: Int) {
        val spacing = stroke.halfWidth * LIGHT_SPACING
        val half = stroke.halfWidth * LIGHT_SIZE
        var next = 0f      // arc-length position of the next bulb
        var acc = 0f       // arc length at the start of the current segment
        var idx = 0

        for (k in 0 until n - 1) {
            val ax = worldBuf[3 * k]; val ay = worldBuf[3 * k + 1]; val az = worldBuf[3 * k + 2]
            val bx = worldBuf[3 * k + 3]; val by = worldBuf[3 * k + 4]; val bz = worldBuf[3 * k + 5]
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-7f) continue

            while (next <= acc + len && idx < MAX_BULBS) {
                val t = (next - acc) / len
                val color = if (stroke.multicolor) BULB_COLORS[idx % BULB_COLORS.size] else stroke.color
                lightRenderer.addBulb(
                    ax + dx * t, ay + dy * t, az + dz * t, half,
                    color[0], color[1], color[2], bulbIntensity(idx), camRight, camUp
                )
                idx++
                next += spacing
            }
            acc += len
        }
    }

    /** Blink pattern: brightness 0.12..1 of bulb [idx] at the current time. */
    private fun bulbIntensity(idx: Int): Float {
        val twoPi = 6.2831855f
        val t = animTime
        val wave = when (lightPattern) {
            // light travels along the string
            LightPattern.CHASE -> 0.5f + 0.5f * sin(twoPi * (t - idx / 10f))
            // odd / even bulbs blink alternately
            LightPattern.ALTERNATE -> 0.5f + 0.5f * sin(twoPi * t + 3.1415927f * (idx % 2))
            // every bulb flickers on its own
            LightPattern.TWINKLE -> {
                val speed = 1 + idx % 3
                val phase = ((idx * 37) % 100) / 100f
                0.5f + 0.5f * sin(twoPi * (t * speed + phase))
            }
        }
        return 0.12f + 0.88f * wave * wave
    }
    // ============================================================ drawing (touch -> 3D)

    fun beginStroke(x: Float, y: Float) {
        val argb = colorArgb
        val b = brush
        val lights = lightsMode
        glView.queueEvent { doBegin(x, y, argb, b, lights) }
    }


    fun addPoint(x: Float, y: Float) {
        glView.queueEvent { doAdd(x, y) }
    }

    fun endStroke() {
        glView.queueEvent { doEnd() }
    }

    fun undo() {
        glView.queueEvent {
            when (val item = history.removeLastOrNull()) {
                is ArStroke -> {
                    strokes.remove(item)
                    item.anchor.detach()
                }

                is ArLabel -> removeLabel(item)
                else -> Unit
            }
            notifyCount()
        }
    }

    fun clear() {
        glView.queueEvent {
            strokes.forEach { it.anchor.detach() }
            strokes.clear()
            ArrayList(labels).forEach { removeLabel(it) }
            history.clear()
            notifyCount()
        }
    }

    private fun removeLabel(label: ArLabel) {
        label.anchor.detach()
        GLES20.glDeleteTextures(1, intArrayOf(label.instance.textureId), 0)
        labels.remove(label)
        labelInstances.remove(label.instance)
    }

    private fun notifyCount() {
        val n = history.size
        main.post { onStrokeCount(n) }
    }

    /** Real surface depth under ([x],[y]) if ARCore knows it, otherwise "in the air". */
    private fun pickDepth(frame: Frame, x: Float, y: Float): Float {
        try {
            for (hit in frame.hitTest(x, y)) {
                val t = hit.trackable
                val usable =
                    (t is Plane && t.isPoseInPolygon(hit.hitPose)) || t is DepthPoint || t is Point
                if (usable) return hit.distance.coerceIn(0.2f, 4f)
            }
        } catch (e: Exception) {
            // use default
        }
        return DEFAULT_DEPTH
    }

    // ============================================================ Air-Write: pin 3D text

    /**
     * Pins [text] in the world where the handwriting was.
     * ([cx], [cy], [wPx], [hPx]) = bounding box of the handwriting in screen pixels.
     */
    fun placeText(text: String, argb: Int, cx: Float, cy: Float, wPx: Float, hPx: Float) {
        val clean = text.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        val bitmap = TextBitmap.create(clean)
        glView.queueEvent { doPlaceLabel(bitmap, argb, cx, cy, wPx, hPx) }
    }

    private fun doPlaceLabel(
        bitmap: Bitmap,
        argb: Int,
        cx: Float,
        cy: Float,
        wPx: Float,
        hPx: Float
    ) {
        val frame = lastFrame
        val s = session
        if (frame == null || s == null || !trackingNow) {
            bitmap.recycle()
            main.post { onMessage("Move your phone slowly until tracking starts") }
            return
        }

        val origin = FloatArray(3)
        val dir = FloatArray(3)
        if (!screenRay(cx, cy, origin, dir)) {
            bitmap.recycle()
            return
        }

        val depth = pickDepth(frame, cx, cy)
        val pos = FloatArray(3) { origin[it] + dir[it] * depth }

        // Anchor with the camera's orientation: local x = right, y = up, z = towards viewer
        val cam = frame.camera.displayOrientedPose
        val anchor = try {
            s.createAnchor(Pose(pos, cam.rotationQuaternion))
        } catch (e: Exception) {
            bitmap.recycle()
            return
        }

        // world metres per screen pixel at the text's distance
        val fwdDist = (pos[0] - origin[0]) * -view[2] + (pos[1] - origin[1]) * -view[6] +
                (pos[2] - origin[2]) * -view[10]
        val perPx = 2f * fwdDist / (proj[5] * viewH)

        val aspect = bitmap.width.toFloat() / bitmap.height
        var width = (wPx * perPx).coerceAtLeast(0.08f)
        var height = width / aspect
        val maxHeight = (hPx * perPx * 1.5f).coerceAtLeast(0.05f)
        if (height > maxHeight) {
            height = maxHeight
            width = height * aspect
        }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        val r = Color.red(argb) / 255f
        val g = Color.green(argb) / 255f
        val b = Color.blue(argb) / 255f
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        val dark = if (luminance < 0.12f) {
            floatArrayOf(0.3f, 0.3f, 0.3f, 1f)
        } else {
            floatArrayOf(r * 0.45f, g * 0.45f, b * 0.45f, 1f)
        }

        val instance = LabelInstance(ids[0], width, height, floatArrayOf(r, g, b, 1f), dark)
        val label = ArLabel(anchor, instance)
        labels.add(label)
        labelInstances.add(instance)
        history.add(label)
        notifyCount()
    }

    private fun doBegin(x: Float, y: Float, argb: Int, brushValue: Float, lights: Boolean) {
        val frame = lastFrame ?: return
        val s = session ?: return
        if (!trackingNow) {
            main.post { onMessage("Move your phone slowly until tracking starts") }
            return
        }

        val origin = FloatArray(3)
        val dir = FloatArray(3)
        if (!screenRay(x, y, origin, dir)) return

        val depth = pickDepth(frame, x, y)

        // Stroke plane: perpendicular to where the camera looks, at that depth
        planeNormal[0] = -view[2]; planeNormal[1] = -view[6]; planeNormal[2] = -view[10]
        for (i in 0..2) planePoint[i] = origin[i] + dir[i] * depth

        val anchor = try {
            s.createAnchor(
                Pose(
                    floatArrayOf(planePoint[0], planePoint[1], planePoint[2]),
                    floatArrayOf(0f, 0f, 0f, 1f)
                )
            )
        } catch (e: Exception) {
            return
        }

        // Same on-screen thickness for near and far strokes
        val px = BRUSH_MIN_PX + brushValue * (BRUSH_MAX_PX - BRUSH_MIN_PX)
        val halfWidth = 0.5f * px * 2f * depth / (proj[5] * viewH)
        val color = floatArrayOf(
            Color.red(argb) / 255f,
            Color.green(argb) / 255f,
            Color.blue(argb) / 255f,
            1f
        )

        // white + lights mode = multicolour bulbs
        val multicolor = lights && (argb and 0xFFFFFF) == 0xFFFFFF
        current = ArStroke(anchor, color, halfWidth, lights, multicolor).also { it.add(0f, 0f, 0f) }    }

    private fun doAdd(x: Float, y: Float) {
        val st = current ?: return
        val origin = FloatArray(3)
        val dir = FloatArray(3)
        if (!screenRay(x, y, origin, dir)) return

        val denom = dir[0] * planeNormal[0] + dir[1] * planeNormal[1] + dir[2] * planeNormal[2]
        if (abs(denom) < 1e-4f) return
        val t = ((planePoint[0] - origin[0]) * planeNormal[0] +
                (planePoint[1] - origin[1]) * planeNormal[1] +
                (planePoint[2] - origin[2]) * planeNormal[2]) / denom
        if (t <= 0.05f) return

        // anchor was created at planePoint with identity rotation -> local = world - planePoint
        val lx = origin[0] + dir[0] * t - planePoint[0]
        val ly = origin[1] + dir[1] * t - planePoint[1]
        val lz = origin[2] + dir[2] * t - planePoint[2]

        val minStep = st.halfWidth * 0.5f
        if (st.distanceSqTo(lx, ly, lz) < minStep * minStep) return
        st.add(lx, ly, lz)
    }

    private fun doEnd() {
        val st = current ?: return
        current = null
        if (st.count >= 2) {
            strokes.add(st)
            history.add(st)
            notifyCount()
        } else {
            st.anchor.detach()
        }
    }

    /** Screen pixel -> world-space ray (origin = camera position). */
    private fun screenRay(x: Float, y: Float, outOrigin: FloatArray, outDir: FloatArray): Boolean {
        if (!Matrix.invertM(invVp, 0, vp, 0)) return false
        val ndcX = 2f * x / viewW - 1f
        val ndcY = 1f - 2f * y / viewH
        val near = FloatArray(4)
        val far = FloatArray(4)
        Matrix.multiplyMV(near, 0, invVp, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        Matrix.multiplyMV(far, 0, invVp, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (near[3] == 0f || far[3] == 0f) return false
        for (i in 0..2) {
            near[i] /= near[3]
            far[i] /= far[3]
        }
        var dx = far[0] - near[0]
        var dy = far[1] - near[1]
        var dz = far[2] - near[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return false
        dx /= len; dy /= len; dz /= len
        outOrigin[0] = camPos[0]; outOrigin[1] = camPos[1]; outOrigin[2] = camPos[2]
        outDir[0] = dx; outDir[1] = dy; outDir[2] = dz
        return true
    }

    // ============================================================ recording

    fun startRecording(withAudio: Boolean) {
        glView.queueEvent {
            if (recorder.isActive) return@queueEvent
            if (recorder.start(viewW, viewH, withAudio)) {
                recStartMs = SystemClock.elapsedRealtime()
                lastElapsedPost = 0L
                main.post { onRecordingChanged(true) }
            } else {
                main.post { onMessage("Unable to start recording") }
            }
        }
    }

    fun stopRecording() {
        if (!recorder.isActive || stopQueued) return
        stopQueued = true
        ioExecutor.execute {
            val file = recorder.stop()
            stopQueued = false
            main.post { onRecordingChanged(false) }
            if (file == null) {
                main.post { onMessage("Recording failed") }
            } else {
                saveToGallery(file)
            }
        }
    }

    private fun saveToGallery(file: File) {
        try {
            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    "ARDoodle_${System.currentTimeMillis()}.mp4"
                )
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SmartClassifier")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create video file")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            main.post { onMessage("Video saved to Movies/SmartClassifier") }
        } catch (e: Exception) {
            main.post { onMessage("Save failed: ${e.message}") }
        } finally {
            file.delete()
        }
    }

    // ============================================================ EGL config (recordable)

    private class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            fun choose(recordable: Boolean): EGLConfig? {
                val attribs = ArrayList<Int>().apply {
                    addAll(
                        listOf(
                            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                            EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                            EGL10.EGL_DEPTH_SIZE, 16,
                            EGL10.EGL_RENDERABLE_TYPE, 4   // EGL_OPENGL_ES2_BIT
                        )
                    )
                    if (recordable) addAll(listOf(0x3142, 1))   // EGL_RECORDABLE_ANDROID
                    add(EGL10.EGL_NONE)
                }
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                egl.eglChooseConfig(display, attribs.toIntArray(), configs, 1, num)
                return if (num[0] > 0) configs[0] else null
            }
            return choose(true) ?: choose(false) ?: throw IllegalStateException("No EGL config")
        }
    }

    private companion object {
        const val TAG = "ArDoodleController"
        const val DEFAULT_DEPTH = 0.6f      // metres, when drawing "in the air"
        const val BRUSH_MIN_PX = 4f
        const val BRUSH_MAX_PX = 40f
        // string-lights look
        const val LIGHT_SPACING = 4.5f      // bulb spacing in stroke half-widths
        const val LIGHT_SIZE = 2.6f         // glow quad half-size in stroke half-widths
        const val WIRE_RATIO = 0.18f
        const val MAX_BULBS = 600
        val WIRE_COLOR = floatArrayOf(0.10f, 0.10f, 0.10f, 1f)
        val BULB_COLORS = arrayOf(
            floatArrayOf(1.00f, 0.20f, 0.20f),   // red
            floatArrayOf(1.00f, 0.75f, 0.20f),   // amber
            floatArrayOf(0.30f, 1.00f, 0.40f),   // green
            floatArrayOf(0.30f, 0.60f, 1.00f),   // blue
            floatArrayOf(1.00f, 0.30f, 0.90f)    // magenta
        )
    }
}