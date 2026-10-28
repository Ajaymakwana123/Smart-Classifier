package com.example.smartclassifier.presentation.ar

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.tasks.await

data class InkPoint(val x: Float, val y: Float, val t: Long)

class InkTextRecognizer {

    private var recognizer: DigitalInkRecognizer? = null
    private var currentTag: String? = null

    suspend fun recognize(strokes: List<List<InkPoint>>, languageTag: String): String {
        return recognizeCandidates(strokes, languageTag).firstOrNull().orEmpty()
    }

    suspend fun recognizeCandidates(strokes: List<List<InkPoint>>, languageTag: String): List<String> {
        val usable = strokes.filter { it.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        val client = clientFor(languageTag)

        val all = usable.flatten()
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }
        val maxY = all.maxOf { it.y }
        val areaW = (maxX - minX).coerceAtLeast(100f)
        val areaH = (maxY - minY).coerceAtLeast(100f)

        // Strokes in time order, timestamps strictly increasing
        val ink = Ink.builder()
        var lastT = 0L
        for (stroke in usable.sortedBy { it.first().t }) {
            val sb = Ink.Stroke.builder()
            for (p in stroke) {
                val t = if (p.t <= lastT) lastT + 1 else p.t
                lastT = t
                sb.addPoint(Ink.Point.create(p.x, p.y, t))
            }
            ink.addStroke(sb.build())
        }
        val builtInk = ink.build()
        if (builtInk.strokes.isEmpty()) return emptyList()

        val context = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(areaW * 1.2f, areaH * 1.2f))
            .build()

        val result = client.recognize(builtInk, context).await()
        return result.candidates.map { it.text.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private suspend fun clientFor(tag: String): DigitalInkRecognizer {
        recognizer?.let { if (currentTag == tag) return it }

        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
            ?: throw IllegalArgumentException("Language not supported: $tag")
        val model = DigitalInkRecognitionModel.builder(id).build()

        val manager = RemoteModelManager.getInstance()
        if (!manager.isModelDownloaded(model).await()) {
            manager.download(model, DownloadConditions.Builder().build()).await()
        }

        recognizer?.close()
        val client = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        recognizer = client
        currentTag = tag
        return client
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        currentTag = null
    }
}