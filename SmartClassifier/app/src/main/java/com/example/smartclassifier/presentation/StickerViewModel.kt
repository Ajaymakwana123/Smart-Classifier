package com.example.smartclassifier.presentation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartclassifier.domain.model.Sticker
import com.example.smartclassifier.domain.usecase.ExtractStickerUseCase
import com.example.smartclassifier.domain.usecase.LoadImageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StickerUiState(
    val source: Bitmap? = null,
    val sticker: Sticker? = null,
    val isBusy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class StickerViewModel @Inject constructor(
    private val loadImageUseCase: LoadImageUseCase,
    private val extractStickerUseCase: ExtractStickerUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(StickerUiState())
    val state: StateFlow<StickerUiState> = _state.asStateFlow()

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, sticker = null) }
            try {
                val bitmap = loadImageUseCase(uri)
                _state.update { it.copy(source = bitmap, sticker = null, isBusy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, message = e.message ?: "Unable to load image") }
            }
        }
    }

    /** Long-press at ([x], [y]) in source-bitmap pixels. */
    fun liftSubject(x: Int, y: Int) {
        val current = _state.value
        val source = current.source ?: return
        if (current.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            try {
                val sticker = extractStickerUseCase(source, x, y)
                _state.update { it.copy(sticker = sticker, isBusy = false) }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val text = if (msg.contains("download", ignoreCase = true)) {
                    "AI model is downloading. Connect to internet and try again in a few seconds."
                } else {
                    msg.ifBlank { "Unable to create sticker" }
                }
                _state.update { it.copy(isBusy = false, message = text) }
            }
        }
    }

    fun clearSticker() = _state.update { it.copy(sticker = null) }

    fun messageShown() = _state.update { it.copy(message = null) }
}
