package com.example.nabu.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nabu.data.ModelManager
import com.example.nabu.kokoro.RunEp
import com.example.nabu.speech.SpeechRequest
import com.example.nabu.speech.SpeechState
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.StyleLoader
import com.example.nabu.kokoro.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for BasicScreen that preserves state across navigation.
 * Manages UI state and coordinates with SpeechForegroundService for TTS operations.
 */
class BasicViewModel(
    private val context: Context
) : ViewModel() {

    private val styleLoader = StyleLoader(context)
    val availableStyles = styleLoader.names.sorted()

    // UI State
    private val _text = MutableStateFlow("Made with love and brought to you from outer space.")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _style = MutableStateFlow(
        SettingsManager.getStyle(context).takeIf { it in availableStyles }
            ?: availableStyles.firstOrNull().orEmpty()
    )
    val style: StateFlow<String> = _style.asStateFlow()

    private val _speed = MutableStateFlow(SettingsManager.getSpeed(context))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _shouldSaveFile = MutableStateFlow(false)
    val shouldSaveFile: StateFlow<Boolean> = _shouldSaveFile.asStateFlow()

    // Model initialization state
    sealed class ModelState {
        object Loading : ModelState()
        object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _hasLocalModels = MutableStateFlow(false)
    val hasLocalModels: StateFlow<Boolean> = _hasLocalModels.asStateFlow()

    init {
        // Initialize models on creation (only once)
        initializeModels()
    }

    fun updateText(newText: String) {
        _text.value = newText
    }

    fun updateStyle(newStyle: String) {
        _style.value = newStyle
        SettingsManager.setStyle(context, newStyle)
    }

    fun updateSpeed(newSpeed: Float) {
        _speed.value = newSpeed
        SettingsManager.setSpeed(context, newSpeed)
    }

    fun updateShouldSaveFile(shouldSave: Boolean) {
        _shouldSaveFile.value = shouldSave
    }

    fun retryModelInitialization() {
        initializeModels()
    }

    private fun initializeModels() {
        viewModelScope.launch {
            _modelState.value = ModelState.Loading

            _hasLocalModels.value = Downloader.modelsAvailable(
                context.applicationContext,
                OnnxRuntimeManager.currentManifest()
            )

            val result = withContext(Dispatchers.IO) {
                OnnxRuntimeManager.initialize(context.applicationContext)
            }

            _modelState.value = result.fold(
                onSuccess = {
                    // Set default style if empty
                    if (_style.value.isEmpty() && availableStyles.isNotEmpty()) {
                        updateStyle(availableStyles.first())
                    }
                    ModelState.Ready
                },
                onFailure = {
                    ModelState.Error(it?.message ?: "Unable to prepare Kokoro models")
                }
            )
        }
    }

    fun createSpeechRequest(): SpeechRequest {
        return SpeechRequest(
            text = _text.value,
            style = _style.value,
            speed = _speed.value,
            shouldSave = _shouldSaveFile.value
        )
    }

    fun getRuntimeDisplayName(): String {
        val runtimePreference = SettingsManager.getRuntimePreference(context)
        return runtimePreference.displayName()
    }

    private fun RunEp.displayName(): String =
        name.lowercase().replaceFirstChar { it.titlecase() }

    override fun onCleared() {
        super.onCleared()
        DebugLogger.log("BasicViewModel: Cleared")
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BasicViewModel::class.java)) {
                return BasicViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
