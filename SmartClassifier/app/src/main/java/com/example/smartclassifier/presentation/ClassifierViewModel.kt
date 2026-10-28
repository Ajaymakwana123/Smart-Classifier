package com.example.smartclassifier.presentation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartclassifier.domain.model.ClassificationResult
import com.example.smartclassifier.domain.model.FaceInfo
import com.example.smartclassifier.domain.usecase.AnalyzeFacesUseCase
import com.example.smartclassifier.domain.usecase.ClassifyImageUseCase
import com.example.smartclassifier.domain.usecase.LoadImageUseCase
import com.example.smartclassifier.domain.usecase.SaveImageUseCase
import com.example.smartclassifier.domain.usecase.SwapFacesUseCase
import com.example.smartclassifier.presentation.util.FaceOverlay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ClassifierUiState {
    data object Idle : ClassifierUiState
    data object Loading : ClassifierUiState
    data class Success(val results: List<ClassificationResult>) : ClassifierUiState
    data class Error(val message: String) : ClassifierUiState
}

data class ClassifierState(
    val baseBitmap: Bitmap? = null,      // clean current image (after edits)
    val displayBitmap: Bitmap? = null,   // same image with face boxes drawn
    val faces: List<FaceInfo> = emptyList(),
    val selectedFaceIds: Set<Int> = emptySet(),
    val labels: ClassifierUiState = ClassifierUiState.Idle,
    val isBusy: Boolean = false,
    val canUndo: Boolean = false,
    val message: String? = null
)

private data class Snapshot(val bitmap: Bitmap, val faces: List<FaceInfo>)

@HiltViewModel
class ClassifierViewModel @Inject constructor(
    private val classifyImageUseCase: ClassifyImageUseCase,
    private val loadImageUseCase: LoadImageUseCase,
    private val analyzeFacesUseCase: AnalyzeFacesUseCase,
    private val swapFacesUseCase: SwapFacesUseCase,
    private val saveImageUseCase: SaveImageUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(ClassifierState())
    val state: StateFlow<ClassifierState> = _state.asStateFlow()

    private val history = ArrayDeque<Snapshot>()
    private var displayJob: Job? = null

    // ------------------------------------------------------------ pick / scan

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            history.clear()
            _state.update {
                it.copy(
                    isBusy = true, labels = ClassifierUiState.Loading, faces = emptyList(),
                    selectedFaceIds = emptySet(), canUndo = false
                )
            }
            try {
                val bitmap = loadImageUseCase(uri)
                val (labels, faces) = coroutineScope {
                    val labelsJob = async { runCatching { classifyImageUseCase(uri) } }
                    val facesJob = async { analyzeFacesUseCase(bitmap) }
                    labelsJob.await() to facesJob.await()
                }
                val selected = if (faces.size == 1) setOf(faces[0].id) else emptySet()
                val display = render(bitmap, faces, selected)
                _state.update {
                    it.copy(
                        baseBitmap = bitmap, displayBitmap = display, faces = faces,
                        selectedFaceIds = selected, isBusy = false, canUndo = false,
                        labels = labels.fold(
                            { r -> ClassifierUiState.Success(r) },
                            { e -> ClassifierUiState.Error(e.message ?: "Unknown error") }
                        )
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        labels = ClassifierUiState.Error(e.message ?: "Unknown error"),
                        message = e.message ?: "Unable to load image"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ selection

    fun toggleFace(id: Int) {
        val current = _state.value.selectedFaceIds
        val updated = if (id in current) current - id else (current + id).toList().takeLast(2).toSet()
        _state.update { it.copy(selectedFaceIds = updated) }
        refreshDisplay()
    }

    private fun refreshDisplay() {
        displayJob?.cancel()
        displayJob = viewModelScope.launch {
            val s = _state.value
            val base = s.baseBitmap ?: return@launch
            val display = render(base, s.faces, s.selectedFaceIds)
            _state.update { it.copy(displayBitmap = display) }
        }
    }

    private suspend fun render(base: Bitmap, faces: List<FaceInfo>, selected: Set<Int>): Bitmap =
        withContext(Dispatchers.Default) { FaceOverlay.draw(base, faces, selected) }

    // ------------------------------------------------------------ edit (face swap)

    fun swapSelectedFaces() {
        val s = _state.value
        val base = s.baseBitmap ?: return
        val picked = s.faces.filter { it.id in s.selectedFaceIds }
        if (picked.size != 2) {
            postMessage("Select exactly 2 faces to swap")
            return
        }
        edit { swapFacesUseCase.swap(base, picked[0], picked[1]) }
    }

    fun swapWithPhoto(uri: Uri) {
        val s = _state.value
        val base = s.baseBitmap ?: return
        val target = s.faces.firstOrNull { it.id in s.selectedFaceIds }
        if (s.selectedFaceIds.size != 1 || target == null) {
            postMessage("Select one face first")
            return
        }
        edit {
            val sourceBitmap = loadImageUseCase(uri)
            val sourceFace = analyzeFacesUseCase(sourceBitmap, analyzePose = false)
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: throw IllegalStateException("No face found in the selected photo")
            swapFacesUseCase.replace(base, target, sourceBitmap, sourceFace)
        }
    }

    private fun edit(block: suspend () -> Bitmap) {
        viewModelScope.launch {
            val before = _state.value
            val oldBase = before.baseBitmap ?: return@launch
            _state.update { it.copy(isBusy = true) }
            try {
                val result = block()
                val faces = analyzeFacesUseCase(result)
                history.addLast(Snapshot(oldBase, before.faces))
                if (history.size > MAX_HISTORY) history.removeFirst()
                val selected = if (faces.size == 1) setOf(faces[0].id) else emptySet()
                val display = render(result, faces, selected)
                _state.update {
                    it.copy(
                        baseBitmap = result, displayBitmap = display, faces = faces,
                        selectedFaceIds = selected, isBusy = false, canUndo = true
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, message = e.message ?: "Edit failed") }
            }
        }
    }

    fun undo() {
        val snapshot = history.removeLastOrNull() ?: return
        viewModelScope.launch {
            val selected = if (snapshot.faces.size == 1) setOf(snapshot.faces[0].id) else emptySet()
            val display = render(snapshot.bitmap, snapshot.faces, selected)
            _state.update {
                it.copy(
                    baseBitmap = snapshot.bitmap, displayBitmap = display, faces = snapshot.faces,
                    selectedFaceIds = selected, canUndo = history.isNotEmpty()
                )
            }
        }
    }

    // ------------------------------------------------------------ save

    fun saveImage() {
        val base = _state.value.baseBitmap ?: run {
            postMessage("Pick an image first")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            try {
                saveImageUseCase(base)
                _state.update { it.copy(isBusy = false, message = "Saved to Pictures/SmartClassifier") }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, message = e.message ?: "Save failed") }
            }
        }
    }

    // ------------------------------------------------------------ messages

    fun postMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private companion object {
        const val MAX_HISTORY = 5
    }
}
