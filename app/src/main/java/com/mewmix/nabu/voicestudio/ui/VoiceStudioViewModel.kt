package com.mewmix.nabu.voicestudio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.voicestudio.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class VoiceStudioState(
    val step: VoiceStudioStep = VoiceStudioStep.READY_TO_IMPORT,
    val text: String = "",
    val selectedVoicePresetId: String? = null,
    val speed: Float = 1.0f,
    val outputPath: String? = null,
    val isGenerating: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

class VoiceStudioViewModel(
    private val presetCatalog: VoicePresetCatalog,
    private val generator: NarrationGenerator,
    private val player: AudioPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceStudioState())
    val state: StateFlow<VoiceStudioState> = _state.asStateFlow()
    val presets get() = presetCatalog.all()

    fun onTextChanged(text: String) { _state.value = _state.value.copy(text = text, step = VoiceStudioStep.EDITING_TEXT) }
    fun onChooseVoice(id: String) { _state.value = _state.value.copy(selectedVoicePresetId = id, step = VoiceStudioStep.CHOOSING_VOICE) }

    fun generate() = viewModelScope.launch {
        val current = _state.value
        val voiceId = current.selectedVoicePresetId ?: return@launch
        _state.value = current.copy(step = VoiceStudioStep.GENERATING, isGenerating = true, error = null)
        runCatching {
            generator.generate(NarrationRequest(UUID.randomUUID().toString(), current.text, voiceId, current.speed, ExportFormat.WAV)) { progress ->
                _state.value = _state.value.copy(progress = progress.fraction)
            }
        }.onSuccess { result ->
            player.load(result.outputPath)
            _state.value = _state.value.copy(step = VoiceStudioStep.COMPLETE, isGenerating = false, outputPath = result.outputPath)
        }.onFailure { e ->
            _state.value = _state.value.copy(step = VoiceStudioStep.FAILED, isGenerating = false, error = e.message)
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()
    override fun onCleared() { player.release() }
}
