package com.example.smartclassifier.data.repository

import android.content.Context
import android.net.Uri
import com.example.smartclassifier.domain.model.ClassificationResult
import com.example.smartclassifier.domain.repository.ImageClassifierRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImageClassifierRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ImageClassifierRepository {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    override suspend fun classifyImage(uri: Uri): List<ClassificationResult> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        return suspendCancellableCoroutine { continuation ->
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    continuation.resume(
                        labels.map { ClassificationResult(label = it.text, confidence = it.confidence) }
                    )
                }
                .addOnFailureListener { e -> continuation.resumeWithException(e) }
        }
    }
}
