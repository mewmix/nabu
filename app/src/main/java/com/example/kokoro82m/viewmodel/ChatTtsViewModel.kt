package com.example.kokoro82m.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtSession
import com.example.kokoro.chat.ChatMessage
import com.example.kokoro.chat.LlmInference
import com.example.kokoro82m.utils.AudioPlayer
import com.example.kokoro82m.utils.InterpolationMode
import com.example.kokoro82m.utils.KittenAudioPlayer
import com.example.kokoro82m.utils.KittenPhonemizer
import com.example.kokoro82m.utils.KokoroAudioPlayer
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.PlayerState
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.DebugLogger
import com.example.kokoro82m.utils.createAudioFromStyleVector
import com.example.kokoro82m.utils.createKittenAudioFromStyleVector
import com.example.kokoro82m.utils.SettingsManager
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.mixStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.StringBuilder

class ChatTtsViewModel(
    private val context: Context,
    private val ortSession: OrtSession,
    private val llmInference: LlmInference
) : ViewModel() {

    // Dependencies
    private val phonemeConverter = PhonemeConverter(context)
    val styleLoader = StyleLoader(context)
    private val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sarah"
    private val audioPlayer: AudioPlayer = when (SettingsManager.getTtsEngine(context)) {
        TtsEngine.KOKORO -> KokoroAudioPlayer(viewModelScope) { newState ->
            _playerState.value = newState
        }
        TtsEngine.KITTEN -> KittenAudioPlayer(viewModelScope) { newState ->
            _playerState.value = newState
        }
    }

    // Chat State
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // TTS State
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing = _isSynthesizing.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()

    // Mixer State
    private val _selectedStyles = MutableStateFlow(listOf(defaultVoice))
    val selectedStyles = _selectedStyles.asStateFlow()

    private val _weights = MutableStateFlow(mapOf(defaultVoice to 1f))
    val weights = _weights.asStateFlow()

    private val _interpolationMode = MutableStateFlow(InterpolationMode.LINEAR)
    val interpolationMode = _interpolationMode.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed = _speed.asStateFlow()

    private val audioQueue = Channel<FloatArray>(Channel.UNLIMITED)

    init {
        llmInference.initialize()
        // Launch a coroutine to play queued audio sequentially
        viewModelScope.launch {
            for (audio in audioQueue) {
                try {
                    audioPlayer.prepare(audio)
                    audioPlayer.playBlocking()
                } catch (e: Exception) {
                    DebugLogger.log("Audio playback error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun sendMessage(message: String) {
        DebugLogger.log("ChatTtsViewModel sendMessage: $message")
        _chatMessages.value += ChatMessage(message, true)
        _isLoading.value = true

        val responseBuilder = StringBuilder()
        val sentenceBuilder = StringBuilder()
        _chatMessages.value += ChatMessage("...", false)  // placeholder

        viewModelScope.launch(Dispatchers.IO) {
            llmInference.sendMessage(message) { partial, done ->
                if (!done) {
                    responseBuilder.append(partial)
                    sentenceBuilder.append(partial)
                    val last = _chatMessages.value.last()
                    _chatMessages.value =
                        _chatMessages.value.dropLast(1) + last.copy(message = responseBuilder.toString())
                    processSentences(sentenceBuilder, false)
                } else {
                    viewModelScope.launch {
                        _isLoading.value = false
                        DebugLogger.log("ChatTtsViewModel response complete")
                        processSentences(sentenceBuilder, true)
                    }
                }
            }
        }
    }

    private fun processSentences(builder: StringBuilder, done: Boolean) {
        var text = builder.toString()
        // Treat consecutive punctuation as a single delimiter and also split on newlines
        val regex = Regex("[.!?]+|\n")
        var match = regex.find(text)
        while (match != null) {
            val end = match.range.last + 1
            val sentence = text.substring(0, end).trim()
            DebugLogger.log("Queueing sentence: $sentence")
            synthesizeAndQueue(sentence)
            text = text.substring(end)
            match = regex.find(text)
        }
        builder.clear()
        builder.append(text)
        if (done && builder.isNotEmpty()) {
            val sentence = builder.toString().trim()
            builder.clear()
            synthesizeAndQueue(sentence)
        }
    }

    private fun synthesizeAndQueue(text: String) {
        viewModelScope.launch {
            _isSynthesizing.value = true
            try {
                DebugLogger.log("Synthesizing: ${text}")
                val audioData = withContext(Dispatchers.IO) {
                    val mixedVector = mixStyles(
                        styleLoader,
                        _selectedStyles.value,
                        _weights.value,
                        _interpolationMode.value
                    )
                    val engine = SettingsManager.getTtsEngine(context)
                    val (data, _) = if (engine == TtsEngine.KITTEN) {
                        val (_, tokens) = KittenPhonemizer.phonemize(text)
                        createKittenAudioFromStyleVector(
                            tokens = tokens,
                            voice = mixedVector,
                            speed = _speed.value,
                            session = ortSession
                        )
                    } else {
                        val phonemes = phonemeConverter.phonemize(text)
                        createAudioFromStyleVector(
                            phonemes = phonemes,
                            voice = mixedVector,
                            speed = _speed.value,
                            session = ortSession
                        )
                    }
                    data
                }
                audioQueue.send(audioData)
            } catch (e: Exception) {
                DebugLogger.log("Error synthesizing sentence: ${e.localizedMessage}")
            } finally {
                _isSynthesizing.value = false
            }
        }
    }

    // --- Mixer State Updaters ---
    fun addStyle(style: String) {
        if (style !in _selectedStyles.value) {
            _selectedStyles.value += style
            _weights.value += (style to 1f)
        }
    }

    fun removeStyle(style: String) {
        _selectedStyles.value -= style
        _weights.value -= style
        if (_selectedStyles.value.isEmpty()) {
            addStyle(defaultVoice) // Ensure at least one style is always selected
        }
    }

    fun updateWeight(style: String, value: Float) {
        _weights.value = _weights.value.toMutableMap().apply { this[style] = value }
    }

    fun updateInterpolationMode(mode: InterpolationMode) {
        _interpolationMode.value = mode
    }

    fun updateSpeed(newSpeed: Float) {
        _speed.value = newSpeed
    }

    override fun onCleared() {
        super.onCleared()
        llmInference.close()
        audioPlayer.stop()
    }
}