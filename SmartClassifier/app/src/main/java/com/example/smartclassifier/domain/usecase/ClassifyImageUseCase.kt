package com.example.smartclassifier.domain.usecase

import android.net.Uri
import com.example.smartclassifier.domain.model.ClassificationResult
import com.example.smartclassifier.domain.repository.ImageClassifierRepository
import javax.inject.Inject

class ClassifyImageUseCase @Inject constructor(
    private val repository: ImageClassifierRepository
) {
    suspend operator fun invoke(uri: Uri): List<ClassificationResult> {
        return repository.classifyImage(uri)
    }
}
