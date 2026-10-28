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

data class AirWriteUiState(
    val color: Int = 0xFFFFFFFF.toInt(),
    val language: String = "en-US",          // ML Kit handwriting language tag
    val itemCount: Int = 0,
    val tracking: Boolean = false,
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val isRecognizing: Boolean = false,
    val error: String? = null,               // AR not available / not installed
    val message: String? = null
)

@HiltViewModel
class AirWriteViewModel @Inject constructor() : ViewModel() {

    private val recognizer = InkTextRecognizer()

    private val _state = MutableStateFlow(AirWriteUiState())
    val state: StateFlow<AirWriteUiState> = _state.asStateFlow()

    fun setColor(color: Int) = _state.update { it.copy(color = color) }
    fun setLanguage(tag: String) = _state.update { it.copy(language = tag) }

    fun onItemCount(count: Int) = _state.update { it.copy(itemCount = count) }
    fun onTracking(tracking: Boolean) = _state.update { it.copy(tracking = tracking) }
    fun onElapsed(ms: Long) = _state.update { it.copy(elapsedMs = ms) }
    fun onRecordingChanged(recording: Boolean) =
        _state.update { it.copy(isRecording = recording, elapsedMs = 0L) }

    fun setError(text: String?) = _state.update { it.copy(error = text) }
    fun postMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }

    /** Handwriting strokes -> text. [onText] is called on the main thread. */
    fun recognize(strokes: List<List<InkPoint>>, onText: (String) -> Unit) {
        val tag = _state.value.language
        viewModelScope.launch {
            _state.update { it.copy(isRecognizing = true) }
            try {
                onText(recognizer.recognize(strokes, tag))
            } catch (e: Exception) {
                postMessage("Could not recognize: ${e.message}")
            } finally {
                _state.update { it.copy(isRecognizing = false) }
            }
        }
    }

    override fun onCleared() {
        recognizer.close()
    }
}