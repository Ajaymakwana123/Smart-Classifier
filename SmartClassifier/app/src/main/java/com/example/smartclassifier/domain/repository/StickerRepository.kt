package com.example.smartclassifier.domain.repository

import android.graphics.Bitmap
import com.example.smartclassifier.domain.model.Sticker

interface StickerRepository {
    /** Cuts out the subject under ([touchX], [touchY]) (source-bitmap pixels) as a sticker. */
    suspend fun extractSubject(source: Bitmap, touchX: Int, touchY: Int): Sticker
}
