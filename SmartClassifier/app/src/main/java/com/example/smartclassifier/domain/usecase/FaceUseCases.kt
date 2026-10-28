package com.example.smartclassifier.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.example.smartclassifier.domain.model.FaceInfo
import com.example.smartclassifier.domain.repository.FaceRepository
import javax.inject.Inject

class LoadImageUseCase @Inject constructor(private val repository: FaceRepository) {
    suspend operator fun invoke(uri: Uri): Bitmap = repository.loadBitmap(uri)
}

class AnalyzeFacesUseCase @Inject constructor(private val repository: FaceRepository) {
    suspend operator fun invoke(bitmap: Bitmap, analyzePose: Boolean = true): List<FaceInfo> =
        repository.detectFaces(bitmap, analyzePose)
}

class SwapFacesUseCase @Inject constructor(private val repository: FaceRepository) {
    /** Swap two faces inside the same image. */
    suspend fun swap(base: Bitmap, first: FaceInfo, second: FaceInfo): Bitmap =
        repository.swapFaces(base, first, second)

    /** Put a face taken from another photo onto [target]. */
    suspend fun replace(
        base: Bitmap,
        target: FaceInfo,
        sourceBitmap: Bitmap,
        sourceFace: FaceInfo
    ): Bitmap = repository.replaceFace(base, target, sourceBitmap, sourceFace)
}

class SaveImageUseCase @Inject constructor(private val repository: FaceRepository) {
    suspend operator fun invoke(bitmap: Bitmap): Uri = repository.saveBitmap(bitmap)
}
