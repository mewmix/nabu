package com.mewmix.nabu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.voicelab.VoiceLabEngineInfo
import com.mewmix.nabu.voicelab.VoiceLabRepository
import com.mewmix.nabu.voicelab.VoiceLabRequest
import com.mewmix.nabu.voicelab.VoiceLabSynthesisResult
import com.mewmix.nabu.voicelab.VoiceLabVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TtsCatalogState(
    val engines: List<VoiceLabEngineInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class TtsWorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VoiceLabRepository(application.applicationContext)
    private val synthesisMutex = Mutex()
    private val voiceCache = mutableMapOf<String, List<VoiceLabVoice>>()
    private val _catalog = MutableStateFlow(TtsCatalogState())
    val catalog: StateFlow<TtsCatalogState> = _catalog.asStateFlow()

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        _catalog.value = _catalog.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.engines() }
            }.onSuccess { engines ->
                voiceCache.keys.retainAll(engines.mapTo(mutableSetOf()) { it.id })
                _catalog.value = TtsCatalogState(engines = engines, isLoading = false)
            }.onFailure { failure ->
                _catalog.value = TtsCatalogState(
                    isLoading = false,
                    error = failure.message ?: failure.toString(),
                )
            }
        }
    }

    suspend fun voices(engineId: String): List<VoiceLabVoice> {
        voiceCache[engineId]?.let { return it }
        return withContext(Dispatchers.IO) { repository.voices(engineId) }
            .also { voiceCache[engineId] = it }
    }

    suspend fun synthesize(request: VoiceLabRequest): VoiceLabSynthesisResult =
        withContext(Dispatchers.IO) {
            synthesisMutex.withLock { repository.synthesize(request) }
        }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
