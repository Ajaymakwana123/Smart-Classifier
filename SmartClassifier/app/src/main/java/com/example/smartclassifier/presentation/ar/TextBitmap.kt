package com.example.smartclassifier.presentation.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/** Renders text as a white-on-transparent bitmap (used as an alpha mask for the 3D label). */
object TextBitmap {

    private const val BASE_SIZE = 160f
    private const val MAX_WIDTH = 2048f

    fun create(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = BASE_SIZE
        }
        var width = paint.measureText(text)
        if (width > MAX_WIDTH) {
            paint.textSize = BASE_SIZE * MAX_WIDTH / width
            width = paint.measureText(text)
        }
        val fm = paint.fontMetrics
        val pad = paint.textSize * 0.12f
        val w = ceil(width + pad * 2).toInt().coerceAtLeast(8)
        val h = ceil(fm.descent - fm.ascent + pad * 2).toInt().coerceAtLeast(8)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, pad, pad - fm.ascent, paint)
        return bitmap
    }
}