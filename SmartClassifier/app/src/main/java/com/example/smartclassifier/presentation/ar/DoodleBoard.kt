package com.example.smartclassifier.presentation.ar

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.example.smartclassifier.domain.model.AnchorMode
import com.example.smartclassifier.domain.model.DoodlePoint
import com.example.smartclassifier.domain.model.DoodleStroke
import com.example.smartclassifier.domain.model.FaceAnchor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Holds all doodle strokes and draws them. Touched only on the main thread
 * (touch events + OverlayEffect draw callback both run on main); [face] is written from the analyzer.
 *
 * WORLD strokes are stored as world-space directions, so they stay on the object you drew on
 * while the camera moves. FACE strokes follow the tracked face.
 */
class DoodleBoard {

    private val strokes = ArrayList<DoodleStroke>()
    private var current: DoodleStroke? = null

    var projection: CameraProjection? = null
    var orientation: OrientationTracker? = null

    @Volatile
    var face: FaceAnchor? = null

    val strokeCount: Int get() = strokes.size

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    // ------------------------------------------------------------ editing

    fun begin(sx: Float, sy: Float, color: Int, widthRatio: Float, faceMode: Boolean) {
        val f = face
        val mode = if (faceMode && f != null) AnchorMode.FACE else AnchorMode.WORLD
        val stroke = DoodleStroke(color, widthRatio, mode)
        encode(sx, sy, stroke, f)?.let { stroke.points.add(it) }
        current = stroke
    }

    fun add(sx: Float, sy: Float) {
        val s = current ?: return
        encode(sx, sy, s, face)?.let { s.points.add(it) }
    }

    /** @return true if a stroke was committed. */
    fun end(): Boolean {
        val s = current ?: return false
        current = null
        if (s.points.isEmpty()) return false
        strokes.add(s)
        return true
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        return true
    }

    fun clear() {
        strokes.clear()
        current = null
    }

    fun resetFace() {
        face = null
    }

    /** sensor point -> stored point (world direction, or face-relative un-rotated by head roll). */
    private fun encode(sx: Float, sy: Float, stroke: DoodleStroke, f: FaceAnchor?): DoodlePoint? {
        return when (stroke.mode) {
            AnchorMode.FACE -> {
                if (f == null) return null
                val rad = Math.toRadians(f.angleDeg.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val dx = sx - f.cx
                val dy = sy - f.cy
                DoodlePoint((dx * c - dy * s) / f.size, (dx * s + dy * c) / f.size)
            }

            AnchorMode.WORLD -> {
                val proj = projection ?: return null
                val d = proj.sensorToWorld(sx, sy, rotation())
                DoodlePoint(d[0], d[1], d[2])
            }
        }
    }

    private fun rotation(): FloatArray = orientation?.rotation() ?: IDENTITY

    fun draw(canvas: Canvas, bufferW: Int, bufferH: Int, sensorToBuffer: Matrix) {
        val base = max(bufferW, bufferH).toFloat()
        val f = face
        val r = rotation()
        for (s in strokes) drawStroke(canvas, s, base, f, r, sensorToBuffer)
        current?.let { drawStroke(canvas, it, base, f, r, sensorToBuffer) }
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: DoodleStroke,
        base: Float,
        f: FaceAnchor?,
        r: FloatArray,
        sensorToBuffer: Matrix
    ) {
        val n = stroke.points.size
        if (n == 0) return

        val pts = FloatArray(n * 2)
        val visible = BooleanArray(n) { true }

        when (stroke.mode) {
            AnchorMode.FACE -> {
                if (f == null) return
                val rad = Math.toRadians(f.angleDeg.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                for (i in 0 until n) {
                    val x = stroke.points[i].x * f.size
                    val y = stroke.points[i].y * f.size
                    pts[2 * i] = f.cx + x * c + y * s
                    pts[2 * i + 1] = f.cy - x * s + y * c
                }
            }

            AnchorMode.WORLD -> {
                val proj = projection ?: return
                for (i in 0 until n) {
                    val p = stroke.points[i]
                    visible[i] = proj.worldToSensor(p.x, p.y, p.z, r, pts, 2 * i)
                }
            }
        }
        sensorToBuffer.mapPoints(pts)

        val width = stroke.widthRatio * base
        if (n == 1) {
            if (visible[0]) {
                dotPaint.color = stroke.color
                canvas.drawCircle(pts[0], pts[1], width / 2f, dotPaint)
            }
            return
        }

        path.reset()
        var penDown = false
        var px = 0f
        var py = 0f
        for (i in 0 until n) {
            if (!visible[i]) {
                if (penDown) {
                    path.lineTo(px, py)
                    penDown = false
                }
                continue
            }
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            if (!penDown) {
                path.moveTo(x, y)
                penDown = true
            } else {
                path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            }
            px = x
            py = y
        }
        if (penDown) path.lineTo(px, py)

        linePaint.color = stroke.color
        linePaint.strokeWidth = width
        canvas.drawPath(path, linePaint)
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}