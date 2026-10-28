package com.example.smartclassifier.domain.model

import android.graphics.Bitmap

/** Cut-out subject (with white outline). [left]/[top] = position inside the source image, in pixels. */
data class Sticker(val bitmap: Bitmap, val left: Int, val top: Int)
