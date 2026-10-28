package com.example.smartclassifier.presentation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ArUiState(
    val color: Int = 0xFFFFFFFF.toInt(),
    val brush: Float = 0.3f,
    val strokeCount: Int = 0,
    val tracking: Boolean = false,
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val lights: Boolean = false,             // string-lights brush
    val lightPattern: Int = 0,               // index into LightPattern
    val error: String? = null,               // AR not available / not installed
    val message: String? = null
)

@HiltViewModel
class ArDoodleViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ArUiState())
    val state: StateFlow<ArUiState> = _state.asStateFlow()

    fun setColor(color: Int) = _state.update { it.copy(color = color) }
    fun setBrush(value: Float) = _state.update { it.copy(brush = value) }
    fun setLights(on: Boolean) = _state.update { it.copy(lights = on) }
    fun setLightPattern(index: Int) = _state.update { it.copy(lightPattern = index) }

    fun onStrokeCount(count: Int) = _state.update { it.copy(strokeCount = count) }
    fun onTracking(tracking: Boolean) = _state.update { it.copy(tracking = tracking) }
    fun onElapsed(ms: Long) = _state.update { it.copy(elapsedMs = ms) }
    fun onRecordingChanged(recording: Boolean) =
        _state.update { it.copy(isRecording = recording, elapsedMs = 0L) }

    fun setError(text: String?) = _state.update { it.copy(error = text) }
    fun postMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }
}