package com.example.smartclassifier.presentation.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.smartclassifier.domain.model.FaceInfo
import kotlin.math.max

object FaceOverlay {

    /** Returns a copy of [base] with numbered boxes around each face (selected = orange). */
    fun draw(base: Bitmap, faces: List<FaceInfo>, selectedIds: Set<Int>): Bitmap {
        if (faces.isEmpty()) return base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val unit = max(out.width, out.height) / 300f

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 1.2f
        }
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 8f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        faces.forEach { face ->
            val color = if (face.id in selectedIds) 0xFFFF9800.toInt() else 0xFF00C853.toInt()
            stroke.color = color
            badge.color = color
            val box = face.boundingBox
            canvas.drawRect(box, stroke)

            val r = unit * 6f
            val cx = (box.left + r).coerceAtMost(out.width - r)
            val cy = (box.top - r).coerceAtLeast(r)
            canvas.drawCircle(cx, cy, r, badge)
            canvas.drawText(face.id.toString(), cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }
        return out
    }
}
