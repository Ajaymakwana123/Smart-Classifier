package com.example.smartclassifier.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.example.smartclassifier.domain.model.FaceInfo

interface FaceRepository {
    suspend fun loadBitmap(uri: Uri): Bitmap
    suspend fun detectFaces(bitmap: Bitmap, analyzePose: Boolean = true): List<FaceInfo>
    suspend fun swapFaces(base: Bitmap, first: FaceInfo, second: FaceInfo): Bitmap
    suspend fun replaceFace(
        base: Bitmap,
        target: FaceInfo,
        sourceBitmap: Bitmap,
        sourceFace: FaceInfo
    ): Bitmap

    suspend fun saveBitmap(bitmap: Bitmap): Uri
}
