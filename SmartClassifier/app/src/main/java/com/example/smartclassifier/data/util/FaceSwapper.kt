package com.example.smartclassifier.data.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/** Pure bitmap face-swap: crop face -> feathered oval mask -> colour match -> rotate -> paste. */
object FaceSwapper {

    private const val PATCH_SIZE = 256
    private const val EXPAND = 1.3f

    fun swap(
        base: Bitmap,
        first: Rect, firstRoll: Float,
        second: Rect, secondRoll: Float
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val patchA = extractPatch(base, first)
        val patchB = extractPatch(base, second)
        val canvas = Canvas(out)
        drawPatch(canvas, patchA, patchB, second, secondRoll - firstRoll)
        drawPatch(canvas, patchB, patchA, first, firstRoll - secondRoll)
        return out
    }

    fun replace(
        base: Bitmap,
        target: Rect, targetRoll: Float,
        source: Bitmap, sourceBox: Rect, sourceRoll: Float
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val sourcePatch = extractPatch(source, sourceBox)
        val targetPatch = extractPatch(base, target)
        drawPatch(Canvas(out), sourcePatch, targetPatch, target, targetRoll - sourceRoll)
        return out
    }

    private fun expand(box: Rect): RectF {
        val w = box.width() * EXPAND
        val h = box.height() * EXPAND
        return RectF(
            box.exactCenterX() - w / 2f, box.exactCenterY() - h / 2f,
            box.exactCenterX() + w / 2f, box.exactCenterY() + h / 2f
        )
    }

    private fun extractPatch(src: Bitmap, box: Rect): Bitmap {
        val region = expand(box)
        val patch = Bitmap.createBitmap(PATCH_SIZE, PATCH_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(patch)

        canvas.save()
        canvas.scale(PATCH_SIZE / region.width(), PATCH_SIZE / region.height())
        canvas.translate(-region.left, -region.top)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        // Soft oval edge so the pasted face blends with the surrounding skin
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = RadialGradient(
                PATCH_SIZE / 2f, PATCH_SIZE / 2f, PATCH_SIZE / 2f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, PATCH_SIZE.toFloat(), PATCH_SIZE.toFloat(), mask)
        return patch
    }

    private fun drawPatch(
        canvas: Canvas,
        patch: Bitmap,
        colorReference: Bitmap,
        targetBox: Rect,
        rotationDelta: Float
    ) {
        val dst = expand(targetBox)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatch(patch, colorReference))
        }
        canvas.save()
        canvas.rotate(-rotationDelta, dst.centerX(), dst.centerY())
        canvas.drawBitmap(patch, null, dst, paint)
        canvas.restore()
    }

    private fun colorMatch(from: Bitmap, to: Bitmap): ColorMatrix {
        val a = averageColor(from)
        val b = averageColor(to)
        fun ratio(target: Float, source: Float) = (target / source.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val r = ratio(b[0], a[0])
        val g = ratio(b[1], a[1])
        val bl = ratio(b[2], a[2])
        return ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, bl, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun averageColor(patch: Bitmap): FloatArray {
        val px = IntArray(PATCH_SIZE * PATCH_SIZE)
        patch.getPixels(px, 0, PATCH_SIZE, 0, 0, PATCH_SIZE, PATCH_SIZE)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            if (Color.alpha(c) > 200) {
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            i += 7
        }
        if (n == 0) return floatArrayOf(128f, 128f, 128f)
        return floatArrayOf(r / n.toFloat(), g / n.toFloat(), b / n.toFloat())
    }
}
