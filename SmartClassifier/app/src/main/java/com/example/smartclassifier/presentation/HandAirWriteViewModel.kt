package com.example.smartclassifier.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartclassifier.presentation.ar.InkPoint
import com.example.smartclassifier.presentation.ar.InkTextRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Supported hand expression modes for Air-Writing. */
enum class GestureMode(val label: String, val icon: String, val tip: String) {
    PINCH(
        "Pinch to Write",
        "🤏",
        "Touch thumb & index finger to write, open hand to hover"
    ),
    INDEX_POINT(
        "Point to Write",
        "☝️",
        "Point index finger out to write, open palm to hover"
    ),
    HOLD_BUTTON(
        "Hold Button",
        "🔘",
        "Hold the on-screen Draw button while moving your hand"
    ),
    GRAB(
        "Grab & Move",
        "✊",
        "Pinch on a text label to grab it, move your hand, release to drop"
    )
}

/** A recognized text label floating on screen. */
data class FloatingLabel(
    val text: String,
    val x: Float,           // centre x (in 0..1 normalised coords)
    val y: Float,           // centre y
    val color: Int,
    val id: Long = System.nanoTime()
)

data class HandAirWriteUiState(
    val color: Int = 0xFFFFFFFF.toInt(),
    val language: String = "en-US",
    val isRecognizing: Boolean = false,
    val handDetected: Boolean = false,
    val isDrawing: Boolean = false,
    val currentGestureName: String = "Waiting for hand...",
    val gestureMode: GestureMode = GestureMode.PINCH,
    val autoConvertEnabled: Boolean = false,
    val candidates: List<String> = emptyList(),
    val labels: List<FloatingLabel> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class HandAirWriteViewModel @Inject constructor() : ViewModel() {

    private val recognizer = InkTextRecognizer()

    private val _state = MutableStateFlow(HandAirWriteUiState())
    val state: StateFlow<HandAirWriteUiState> = _state.asStateFlow()

    fun setColor(color: Int) = _state.update { it.copy(color = color) }
    fun setLanguage(tag: String) = _state.update { it.copy(language = tag) }

    fun setGestureMode(mode: GestureMode) = _state.update {
        it.copy(
            gestureMode = mode,
            currentGestureName = mode.tip
        )
    }

    fun setAutoConvertEnabled(enabled: Boolean) = _state.update {
        it.copy(autoConvertEnabled = enabled)
    }

    fun setHandDetected(detected: Boolean) = _state.update {
        it.copy(
            handDetected = detected,
            currentGestureName = if (!detected) "Wave your hand in front of camera 👋" else it.currentGestureName
        )
    }

    fun setDrawing(drawing: Boolean) = _state.update { it.copy(isDrawing = drawing) }

    fun setCurrentGesture(name: String) = _state.update { it.copy(currentGestureName = name) }

    fun postMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun clearLabels() = _state.update { it.copy(labels = emptyList(), candidates = emptyList()) }
    fun undoLabel() = _state.update { s ->
        s.copy(labels = s.labels.dropLast(1))
    }

    /** Move a floating label (x,y in 0..1 normalised coords). */
    fun moveLabel(id: Long, x: Float, y: Float) = _state.update { s ->
        s.copy(labels = s.labels.map { if (it.id == id) it.copy(x = x, y = y) else it })
    }

    fun selectCandidate(text: String) = _state.update { s ->
        if (s.labels.isEmpty()) s
        else s.copy(labels = s.labels.dropLast(1) + s.labels.last().copy(text = text))
    }

    fun recognize(strokes: List<List<InkPoint>>, centreX: Float, centreY: Float) {
        if (_state.value.isRecognizing) return
        val totalPoints = strokes.sumOf { it.size }
        if (totalPoints < 3) {
            postMessage("Writing too short. Write letters clearly in the air.")
            return
        }
        val tag = _state.value.language
        val color = _state.value.color
        viewModelScope.launch {
            _state.update { it.copy(isRecognizing = true) }
            try {
                val candidates = recognizer.recognizeCandidates(strokes, tag)
                val topText = candidates.firstOrNull()?.trim()

                if (topText.isNullOrBlank()) {
                    postMessage("Could not read writing. Try writing bigger and clearer.")
                } else {
                    _state.update { s ->
                        s.copy(
                            labels = s.labels + FloatingLabel(topText, centreX, centreY, color),
                            candidates = candidates
                        )
                    }
                }
            } catch (e: Exception) {
                postMessage("Recognition error: ${e.message}")
            } finally {
                _state.update { it.copy(isRecognizing = false) }
            }
        }
    }

    override fun onCleared() {
        recognizer.close()
    }
}