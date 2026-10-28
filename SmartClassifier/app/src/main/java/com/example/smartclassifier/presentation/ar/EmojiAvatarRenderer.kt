package com.example.smartclassifier.presentation.ar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.example.smartclassifier.domain.model.EmojiFace
import com.example.smartclassifier.domain.model.EmojiStyle

class EmojiAvatarRenderer {

    @Volatile var face: EmojiFace? = null
    @Volatile var avatar: EmojiStyle = EmojiStyle.CLASSIC

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, bufferW: Int, bufferH: Int, sensorToBuffer: Matrix) {
        val f = face ?: return
        if (SystemClock.uptimeMillis() - f.timeMs > MAX_AGE_MS) return   // face lost

        val m = Matrix(f.uprightToSensor)
        m.postConcat(sensorToBuffer)

        canvas.save()
        canvas.concat(m)
        drawHead(canvas, f)
        drawBrow(canvas, f, f.browLeft)
        drawBrow(canvas, f, f.browRight)
        drawEye(canvas, f, f.eyeLeft, f.openLeft)
        drawEye(canvas, f, f.eyeRight, f.openRight)
        drawNose(canvas, f)
        drawMouth(canvas, f)
        canvas.restore()
    }

    // ------------------------------------------------------------ head

    private fun drawHead(canvas: Canvas, f: EmojiFace) {
        val rx = f.width * 0.64f
        val ry = f.height * 0.68f
        canvas.save()
        canvas.rotate(-f.rollDeg, f.cx, f.cy)
        rect.set(f.cx - rx, f.cy - ry, f.cx + rx, f.cy + ry)
        line.strokeWidth = f.width * 0.025f

        when (avatar) {
            EmojiStyle.CLASSIC -> {
                fill.shader = RadialGradient(
                    f.cx, f.cy - ry * 0.3f, ry * 1.3f,
                    0xFFFFE680.toInt(), 0xFFFFB300.toInt(), Shader.TileMode.CLAMP
                )
                canvas.drawOval(rect, fill)
                fill.shader = null
                line.color = 0xFFE69500.toInt()
                canvas.drawOval(rect, line)
            }
            EmojiStyle.CAT -> {
                // ears (behind head)
                for (side in intArrayOf(-1, 1)) {
                    fill.color = 0xFFEF8F00.toInt()
                    path.reset()
                    path.moveTo(f.cx + side * rx * 0.95f, f.cy - ry * 0.30f)
                    path.lineTo(f.cx + side * rx * 0.80f, f.cy - ry * 1.30f)
                    path.lineTo(f.cx + side * rx * 0.10f, f.cy - ry * 0.85f)
                    path.close()
                    canvas.drawPath(path, fill)
                    fill.color = 0xFFFFB3C1.toInt()
                    path.reset()
                    path.moveTo(f.cx + side * rx * 0.82f, f.cy - ry * 0.55f)
                    path.lineTo(f.cx + side * rx * 0.74f, f.cy - ry * 1.08f)
                    path.lineTo(f.cx + side * rx * 0.30f, f.cy - ry * 0.80f)
                    path.close()
                    canvas.drawPath(path, fill)
                }
                fill.color = 0xFFFFA726.toInt()
                canvas.drawOval(rect, fill)
                line.color = 0xFFEF8F00.toInt()
                canvas.drawOval(rect, line)
            }
            EmojiStyle.PANDA -> {
                fill.color = Color.BLACK
                for (side in intArrayOf(-1, 1)) {
                    canvas.drawCircle(f.cx + side * rx * 0.72f, f.cy - ry * 0.86f, rx * 0.30f, fill)
                }
                fill.color = Color.WHITE
                canvas.drawOval(rect, fill)
                line.color = 0xFF9E9E9E.toInt()
                canvas.drawOval(rect, line)
            }
            EmojiStyle.ROBOT -> {
                line.color = 0xFF607D8B.toInt()
                line.strokeWidth = f.width * 0.03f
                canvas.drawLine(f.cx, f.cy - ry, f.cx, f.cy - ry * 1.35f, line)
                fill.color = 0xFFFF1744.toInt()
                canvas.drawCircle(f.cx, f.cy - ry * 1.38f, rx * 0.12f, fill)
                fill.color = 0xFF90A4AE.toInt()
                for (side in intArrayOf(-1, 1)) {
                    rect.set(
                        f.cx + side * rx - rx * 0.10f, f.cy - ry * 0.25f,
                        f.cx + side * rx + rx * 0.10f, f.cy + ry * 0.25f
                    )
                    canvas.drawRoundRect(rect, 8f, 8f, fill)
                }
                rect.set(f.cx - rx, f.cy - ry, f.cx + rx, f.cy + ry)
                fill.color = 0xFFCFD8DC.toInt()
                canvas.drawRoundRect(rect, rx * 0.35f, rx * 0.35f, fill)
                line.color = 0xFF607D8B.toInt()
                line.strokeWidth = f.width * 0.025f
                canvas.drawRoundRect(rect, rx * 0.35f, rx * 0.35f, line)
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------ features

    /** Draw in a local frame centred on [p] and rotated with the head. */
    private inline fun at(canvas: Canvas, p: PointF, roll: Float, block: () -> Unit) {
        canvas.save()
        canvas.translate(p.x, p.y)
        canvas.rotate(-roll)
        block()
        canvas.restore()
    }

    private fun drawBrow(canvas: Canvas, f: EmojiFace, p: PointF) {
        if (avatar == EmojiStyle.PANDA || avatar == EmojiStyle.ROBOT) return
        val u = f.width
        at(canvas, p, f.rollDeg) {
            line.color = 0xFF5D4037.toInt()
            line.strokeWidth = u * 0.035f
            canvas.drawLine(-u * 0.08f, 0f, u * 0.08f, 0f, line)
        }
    }

    private fun drawEye(canvas: Canvas, f: EmojiFace, p: PointF, open: Float) {
        val u = f.width
        at(canvas, p, f.rollDeg) {
            if (avatar == EmojiStyle.ROBOT) {
                val w = u * 0.17f
                val h = u * 0.13f * (0.12f + 0.88f * open)
                rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
                fill.color = 0xFF00E5FF.toInt()
                canvas.drawRoundRect(rect, h * 0.3f, h * 0.3f, fill)
            } else {
                if (avatar == EmojiStyle.PANDA) {
                    fill.color = Color.BLACK
                    rect.set(-u * 0.15f, -u * 0.19f, u * 0.15f, u * 0.19f)
                    canvas.drawOval(rect, fill)
                }
                if (open < 0.3f) {
                    // closed eye
                    line.color = if (avatar == EmojiStyle.PANDA) Color.WHITE else 0xFF3E2723.toInt()
                    line.strokeWidth = u * 0.035f
                    path.reset()
                    path.moveTo(-u * 0.07f, 0f)
                    path.quadTo(0f, u * 0.05f, u * 0.07f, 0f)
                    canvas.drawPath(path, line)
                } else {
                    val rx = u * 0.065f
                    val ry = u * 0.095f * (0.35f + 0.65f * open)
                    if (avatar == EmojiStyle.PANDA) {
                        fill.color = Color.WHITE
                        rect.set(-rx * 1.2f, -ry * 1.2f, rx * 1.2f, ry * 1.2f)
                        canvas.drawOval(rect, fill)
                    }
                    fill.color = if (avatar == EmojiStyle.PANDA) Color.BLACK else 0xFF3E2723.toInt()
                    rect.set(-rx, -ry, rx, ry)
                    canvas.drawOval(rect, fill)
                    fill.color = Color.WHITE
                    canvas.drawCircle(-rx * 0.30f, -ry * 0.35f, rx * 0.35f, fill)
                }
            }
        }
    }

    private fun drawNose(canvas: Canvas, f: EmojiFace) {
        val u = f.width
        when (avatar) {
            EmojiStyle.CAT -> at(canvas, f.nose, f.rollDeg) {
                fill.color = 0xFFFF6F91.toInt()
                path.reset()
                path.moveTo(0f, u * 0.045f)
                path.lineTo(-u * 0.045f, -u * 0.015f)
                path.lineTo(u * 0.045f, -u * 0.015f)
                path.close()
                canvas.drawPath(path, fill)
                line.color = 0xFF5D4037.toInt()
                line.strokeWidth = u * 0.012f
                for (side in intArrayOf(-1, 1)) {
                    canvas.drawLine(side * u * 0.12f, u * 0.0f, side * u * 0.36f, -u * 0.04f, line)
                    canvas.drawLine(side * u * 0.12f, u * 0.04f, side * u * 0.36f, u * 0.06f, line)
                }
            }
            EmojiStyle.PANDA -> at(canvas, f.nose, f.rollDeg) {
                fill.color = Color.BLACK
                rect.set(-u * 0.05f, -u * 0.035f, u * 0.05f, u * 0.035f)
                canvas.drawOval(rect, fill)
            }
            else -> Unit
        }
    }

    private fun drawMouth(canvas: Canvas, f: EmojiFace) {
        val u = f.width
        val mw = f.mouthWidth.coerceIn(u * 0.22f, u * 0.55f)
        val half = mw / 2f
        val open = f.mouthOpen
        val smileCurve = (f.smile - 0.35f) * u * 0.30f   // + = smile (centre lower than corners)

        at(canvas, f.mouth, f.rollDeg) {
            if (avatar == EmojiStyle.ROBOT) {
                val h = u * (0.04f + 0.14f * open)
                rect.set(-half, -h / 2f, half, h / 2f)
                fill.color = 0xFF263238.toInt()
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                line.color = 0xFF90A4AE.toInt()
                line.strokeWidth = u * 0.012f
                for (i in 1..4) {
                    val x = -half + mw * i / 5f
                    canvas.drawLine(x, -h / 2f, x, h / 2f, line)
                }
                return@at
            }

            if (open > 0.12f) {
                val depth = u * (0.05f + 0.22f * open)
                path.reset()
                path.moveTo(-half, 0f)
                path.quadTo(0f, smileCurve, half, 0f)
                path.quadTo(0f, depth * 2f + smileCurve, -half, 0f)
                path.close()
                fill.color = 0xFF5D1A1A.toInt()
                canvas.drawPath(path, fill)
                if (open > 0.45f) {
                    canvas.save()
                    canvas.clipPath(path)
                    fill.color = 0xFFFF7A8A.toInt()
                    rect.set(-half * 0.5f, depth * 0.9f, half * 0.5f, depth * 1.6f)
                    canvas.drawOval(rect, fill)
                    canvas.restore()
                }
                line.color = 0xFF3E2723.toInt()
                line.strokeWidth = u * 0.022f
                canvas.drawPath(path, line)
            } else {
                line.color = if (avatar == EmojiStyle.PANDA) 0xFF212121.toInt() else 0xFF3E2723.toInt()
                line.strokeWidth = u * 0.03f
                path.reset()
                path.moveTo(-half, 0f)
                path.quadTo(0f, smileCurve * 1.6f, half, 0f)
                canvas.drawPath(path, line)
            }
        }
    }

    private companion object {
        const val MAX_AGE_MS = 350L
    }
}