package com.example.smartclassifier.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.smartclassifier.domain.model.Sticker
import com.example.smartclassifier.domain.repository.StickerRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.Subject
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class StickerRepositoryImpl @Inject constructor() : StickerRepository {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableSubjectBitmap()
                    .build()
            )
            .build()
    )

    override suspend fun extractSubject(source: Bitmap, touchX: Int, touchY: Int): Sticker {
        val subjects = segmenter.process(InputImage.fromBitmap(source, 0)).await().subjects
        if (subjects.isEmpty()) throw IllegalStateException("No subject found in this image")

        return withContext(Dispatchers.Default) {
            val radius = (max(source.width, source.height) / 60).coerceAtLeast(8)

            val picked = subjects
                .map { normalize(it, source.width, source.height) }
                .filter { it.hit(touchX, touchY, radius) }
                .minByOrNull { it.bitmap!!.width * it.bitmap.height }
                ?: throw IllegalStateException("No subject here. Long-press on a person or object.")

            val stroke = (max(picked.bitmap!!.width, picked.bitmap.height) / 60).coerceIn(6, 24)
            val pad = stroke + 2
            Sticker(
                bitmap = addOutline(picked.bitmap, stroke, pad),
                left = picked.left - pad,
                top = picked.top - pad
            )
        }
    }

    private class Cutout(val bitmap: Bitmap?, val left: Int, val top: Int) {
        fun hit(x: Int, y: Int, r: Int): Boolean {
            val offsets = listOf(
                0 to 0, r to 0, -r to 0, 0 to r, 0 to -r, r to r, -r to -r, r to -r, -r to r
            )
            return offsets.any { (dx, dy) ->
                val bx = x + dx - left
                val by = y + dy - top
                bx in 0 until bitmap!!.width && by in 0 until bitmap.height &&
                    Color.alpha(bitmap.getPixel(bx, by)) > 40
            }
        }
    }

    private fun normalize(subject: Subject, srcW: Int, srcH: Int): Cutout {
        val bmp = subject.bitmap
        return if (bmp?.width == srcW && bmp.height == srcH && (subject.width != srcW || subject.height != srcH)) {
            // Full-frame mask -> crop to the subject's box
            val x = subject.startX.coerceIn(0, srcW - 1)
            val y = subject.startY.coerceIn(0, srcH - 1)
            val w = subject.width.coerceIn(1, srcW - x)
            val h = subject.height.coerceIn(1, srcH - y)
            Cutout(Bitmap.createBitmap(bmp, x, y, w, h), x, y)
        } else {
            Cutout(bmp, subject.startX, subject.startY)
        }
    }

    /** Samsung-style white sticker border. */
    private fun addOutline(cutout: Bitmap?, stroke: Int, pad: Int): Bitmap {
        val out = Bitmap.createBitmap(
            cutout!!.width + pad * 2, cutout.height + pad * 2, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val steps = 24
        for (ring in 1..2) {
            val r = stroke * ring / 2f
            for (i in 0 until steps) {
                val a = 2.0 * PI * i / steps
                if (cutout != null) {
                    canvas.drawBitmap(
                        cutout, pad + (cos(a) * r).toFloat(), pad + (sin(a) * r).toFloat(), white
                    )
                }
            }
        }
        if (cutout != null) {
            canvas.drawBitmap(cutout, pad.toFloat(), pad.toFloat(), null)
        }
        return out
    }
}
