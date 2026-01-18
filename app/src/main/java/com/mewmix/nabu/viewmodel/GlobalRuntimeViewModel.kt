package com.mewmix.nabu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.data.ModelState
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
            
            val isSupertonic = SettingsManager.getTtsEngine(context) == "supertonic"
            
            if (isSupertonic) {
                // Supertonic logic
                _modelState.value = ModelState.Ready
            } else {
                // Kokoro logic
                if (Downloader.modelsAvailable(context, OnnxRuntimeManager.currentManifest())) {
                    // Models exist, init runtime
                    val result = OnnxRuntimeManager.initialize(
                        context,
                        allowDownload = SettingsManager.isKokoroAutoDownloadEnabled(context),
                        onProgress = { progress -> _downloadProgress.value = progress }
                    )
                    
                    _modelState.value = result.fold(
                        onSuccess = { ModelState.Ready },
                        onFailure = { ModelState.Error(it?.message ?: "Runtime init failed") }
                    )
                } else {
                    // Need download
                    if (SettingsManager.isKokoroAutoDownloadEnabled(context)) {
                         val result = OnnxRuntimeManager.initialize(
                            context,
                            allowDownload = true,
                            onProgress = { progress -> _downloadProgress.value = progress }
                        )
                         _modelState.value = result.fold(
                            onSuccess = { ModelState.Ready },
                            onFailure = { ModelState.Error(it?.message ?: "Download failed") }
                        )
                    } else {
                        // Prompt user
                        _modelState.value = ModelState.Error("Voice models required")
                    }
                }
            }
        }
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
