package com.mewmix.nabu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelDownloader
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelState
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.UserPreferencesRepository
import com.mewmix.nabu.kokoro.Downloader
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages global application state including TTS runtime initialization
 * and benchmark statistics.
 *
 * Helps prevent runtime re-initialization on tab switches.
 * Inspired by architectural improvements in PR #72.
 */
class GlobalRuntimeViewModel(application: Application) : AndroidViewModel(application) {

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Downloader.DownloadProgress?>(null)
    val downloadProgress: StateFlow<Downloader.DownloadProgress?> = _downloadProgress.asStateFlow()

    // Benchmark stats
    private val _benchmarkStats = MutableStateFlow<Map<String, Float>>(emptyMap())
    val benchmarkStats: StateFlow<Map<String, Float>> = _benchmarkStats.asStateFlow()

    init {
        initializeRuntime()
    }

    fun initializeRuntime() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            _modelState.value = ModelState.Loading

            val modelManager = ModelManager(context)
            val downloader = ModelDownloader(context, UserPreferencesRepository(context))
            when (SettingsManager.getTtsEngine(context)) {
                "supertonic" -> {
                    val model = resolveSupertonicModel(context, modelManager)
                    if (model == null) {
                        _modelState.value = ModelState.Error("No Supertonic model configured")
                        return@launch
                    }
                    if (!model.isDownloaded) {
                        val downloaded = downloader.ensureModelDownloaded(model)
                        if (!downloaded) {
                            _modelState.value = ModelState.Error("Supertonic model download incomplete")
                            return@launch
                        }
                    }
                    _modelState.value = ModelState.Ready
                    return@launch
                }
                "soprano" -> {
                    val model = modelManager.getModel("soprano-80m-onnx")
                    if (model == null) {
                        _modelState.value = ModelState.Error("Soprano model missing from allowlist")
                        return@launch
                    }
                    if (!model.isDownloaded) {
                        val downloaded = downloader.ensureModelDownloaded(model)
                        if (!downloaded) {
                            _modelState.value = ModelState.Error("Soprano model download incomplete")
                            return@launch
                        }
                    }
                    _modelState.value = ModelState.Ready
                    return@launch
                }
                else -> {
                    val canAutoDownload = SettingsManager.isKokoroAutoDownloadEnabled(context)
                    val kokoroReady = if (canAutoDownload) {
                        downloader.ensureKokoroDefaultDownloaded()
                    } else {
                        Downloader.modelsAvailable(context, OnnxRuntimeManager.currentManifest())
                    }
                    if (!kokoroReady) {
                        _modelState.value = ModelState.Error("Voice models required")
                        return@launch
                    }

                    val result = OnnxRuntimeManager.initialize(
                        context,
                        allowDownload = false,
                        onProgress = { progress -> _downloadProgress.value = progress }
                    )
                    _modelState.value = result.fold(
                        onSuccess = { ModelState.Ready },
                        onFailure = { ModelState.Error(it?.message ?: "Runtime init failed") }
                    )
                }
            }
        }
    }

    private fun resolveSupertonicModel(context: Application, modelManager: ModelManager): Model? {
        val supertonicModels = modelManager.models.filter {
            it.type == ModelType.TTS && it.id.startsWith("supertonic")
        }
        if (supertonicModels.isEmpty()) return null
        val preferredId = SettingsManager.getSupertonicModelId(context)
        val selected = preferredId?.let { id -> supertonicModels.firstOrNull { it.id == id } }
            ?: supertonicModels.firstOrNull()
        if (preferredId.isNullOrBlank() && selected != null) {
            SettingsManager.setSupertonicModelId(context, selected.id)
        }
        return selected
    }
    
    fun updateBenchmarkStat(label: String, value: Float) {
        val newStats = _benchmarkStats.value.toMutableMap()
        newStats[label] = value
        _benchmarkStats.value = newStats
    }
    
    fun retryInitialization() {
        initializeRuntime()
    }
}
