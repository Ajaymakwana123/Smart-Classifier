package com.example.smartclassifier.domain.repository

import android.net.Uri
import com.example.smartclassifier.domain.model.ClassificationResult

interface ImageClassifierRepository {
    suspend fun classifyImage(uri: Uri): List<ClassificationResult>
}
