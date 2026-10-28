package com.example.smartclassifier.presentation.ar

import android.content.Context
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Records the AR scene (camera + doodles) by rendering it a second time into the
 * MediaRecorder input surface, using the GLSurfaceView's own EGL context.
 * start()/render() must be called on the GL thread; stop() may be called from any thread.
 */
class ArVideoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var file: File? = null

    var width = 0
        private set
    var height = 0
        private set

    @Volatile
    var isActive = false
        private set

    @Synchronized
    fun start(viewW: Int, viewH: Int, withAudio: Boolean): Boolean {
        if (isActive || viewW <= 0 || viewH <= 0) return false

        val scale = min(1f, MAX_SIDE / max(viewW, viewH).toFloat())
        width = ((viewW * scale).toInt() and 1.inv()).coerceAtLeast(2)
        height = ((viewH * scale).toInt() and 1.inv()).coerceAtLeast(2)

        val out = File(context.cacheDir, "ardoodle_${System.currentTimeMillis()}.mp4")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(out.absolutePath)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128_000)
            }
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(8_000_000)
            r.prepare()
        } catch (e: Exception) {
            r.release()
            out.delete()
            return false
        }

        // EGL window surface on the encoder surface, same config as the GLSurfaceView context
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val configId = IntArray(1)
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, configId, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            dpy, intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE), 0,
            configs, 0, 1, num, 0
        )
        val surface = if (num[0] > 0) {
            EGL14.eglCreateWindowSurface(dpy, configs[0], r.surface, intArrayOf(EGL14.EGL_NONE), 0)
        } else EGL14.EGL_NO_SURFACE

        if (surface == EGL14.EGL_NO_SURFACE) {
            r.release()
            out.delete()
            return false
        }

        try {
            r.start()
        } catch (e: Exception) {
            EGL14.eglDestroySurface(dpy, surface)
            r.release()
            out.delete()
            return false
        }

        recorder = r
        display = dpy
        eglSurface = surface
        file = out
        isActive = true
        return true
    }

    /** Render one frame into the recording surface. [draw] receives (width, height). */
    @Synchronized
    fun render(timestampNs: Long, draw: (Int, Int) -> Unit) {
        if (!isActive) return
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        if (!EGL14.eglMakeCurrent(dpy, eglSurface, eglSurface, ctx)) return
        try {
            draw(width, height)
            EGLExt.eglPresentationTimeANDROID(dpy, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(dpy, eglSurface)
        } finally {
            EGL14.eglMakeCurrent(dpy, prevDraw, prevRead, ctx)
        }
    }

    /** Stops recording. Returns the temp mp4 file, or null if it failed. */
    @Synchronized
    fun stop(): File? {
        if (!isActive) return null
        isActive = false

        var result = file
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            result?.delete()    // stop() fails when no frames were written
            result = null
        }
        recorder?.release()
        recorder = null

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        file = null
        return result
    }

    private companion object {
        const val MAX_SIDE = 1280f
    }
}