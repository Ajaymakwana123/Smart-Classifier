package com.example.smartclassifier.presentation

import androidx.lifecycle.ViewModel
import com.example.smartclassifier.domain.model.EmojiStyle
import com.example.smartclassifier.presentation.ar.EmojiAvatarRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class EmojiUiState(
    val style: EmojiStyle = EmojiStyle.CLASSIC,
    val useFrontCamera: Boolean = true,
    val videoMode: Boolean = false,
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val faceVisible: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class EmojiViewModel @Inject constructor() : ViewModel() {

    val renderer = EmojiAvatarRenderer()

    private val _state = MutableStateFlow(EmojiUiState())
    val state: StateFlow<EmojiUiState> = _state.asStateFlow()

    fun setStyle(style: EmojiStyle) {
        renderer.avatar = style
        _state.update { it.copy(style = style) }
    }

    fun setVideoMode(video: Boolean) = _state.update { it.copy(videoMode = video) }
    fun flipCamera() = _state.update { it.copy(useFrontCamera = !it.useFrontCamera) }
    fun onFaceVisible(visible: Boolean) = _state.update { it.copy(faceVisible = visible) }
    fun onRecordingStarted() = _state.update { it.copy(isRecording = true, elapsedMs = 0L) }

    /** Controller reports elapsed ms while recording, -1 when recording ended. */
    fun onElapsed(ms: Long) = _state.update {
        if (ms < 0) it.copy(isRecording = false, elapsedMs = 0L) else it.copy(elapsedMs = ms)
    }

    fun postMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }
}