package com.example.smartclassifier.domain.usecase

import android.graphics.Bitmap
import com.example.smartclassifier.domain.model.Sticker
import com.example.smartclassifier.domain.repository.StickerRepository
import javax.inject.Inject

class ExtractStickerUseCase @Inject constructor(private val repository: StickerRepository) {
    suspend operator fun invoke(source: Bitmap, touchX: Int, touchY: Int): Sticker =
        repository.extractSubject(source, touchX, touchY)
}
