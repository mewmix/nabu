package com.example.nabu.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtSession
import com.example.kokoro.chat.ChatMessage
import com.example.kokoro.chat.LlmInference
import com.example.kokoro.chat.LlmMessage
import com.example.nabu.data.Conversation
import com.example.nabu.data.ConversationRepository
import com.example.nabu.data.ConversationRole
import com.example.nabu.data.ConversationSummary
import com.example.nabu.data.ConversationTurn
import com.example.nabu.data.Model
import com.example.nabu.data.ModelManager
import com.example.nabu.utils.AudioPlayer
import com.example.nabu.utils.BenchmarkManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.InterpolationMode
import com.example.nabu.utils.KittenAudioPlayer
import com.example.nabu.utils.KittenPhonemizer
import com.example.nabu.utils.KokoroAudioPlayer
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.PlayerState
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.TtsEngine
import com.example.nabu.utils.createAudioFromStyleVector
import com.example.nabu.utils.createKittenAudioFromStyleVector
import com.example.nabu.utils.mixStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.tryReceive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.StringBuilder

class ChatViewModel(
    private val context: Context,
    private val ortSession: OrtSession,
    initialModelId: String
) : ViewModel() {

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 1024
        private val TOKEN_REGEX = Regex("\\S+")
    }

    // Dependencies
    private val phonemeConverter = PhonemeConverter(context)
    val styleLoader = StyleLoader(context)
    private val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sky"
    private val audioPlayer: AudioPlayer = when (SettingsManager.getTtsEngine(context)) {
        TtsEngine.KOKORO -> KokoroAudioPlayer(viewModelScope) { newState ->
            _playerState.value = newState
        }
        TtsEngine.KITTEN -> KittenAudioPlayer(viewModelScope) { newState ->
            _playerState.value = newState
        }
    }

    private val modelManager = ModelManager(context)
    private var llmInference: LlmInference? = null
    private val conversationHistory = mutableListOf<ConversationTurn>()

    // Chat State
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Conversation State
    private val _conversationSummaries = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversationSummaries = _conversationSummaries.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId = _activeConversationId.asStateFlow()

    private val _availableModels = MutableStateFlow<List<Model>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _activeModel = MutableStateFlow<Model?>(null)
    val activeModel = _activeModel.asStateFlow()

    // TTS State
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing = _isSynthesizing.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(SettingsManager.isTtsEnabled(context))
    val ttsEnabled = _ttsEnabled.asStateFlow()

    // Mixer State
    private val _selectedStyles = MutableStateFlow(listOf(defaultVoice))
    val selectedStyles = _selectedStyles.asStateFlow()

    private val _weights = MutableStateFlow(mapOf(defaultVoice to 1f))
    val weights = _weights.asStateFlow()

    private val _interpolationMode = MutableStateFlow(InterpolationMode.LINEAR)
    val interpolationMode = _interpolationMode.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed = _speed.asStateFlow()

    private val audioQueue = Channel<Pair<Int, FloatArray>>(Channel.UNLIMITED)
    private val pendingAudio = mutableMapOf<Int, FloatArray>()
    private var nextPlaybackIndex = 0
    private var dropQueuedAudio = false
    private var lineIndex = 0

    init {
        // Launch a coroutine to play queued audio in the order they were generated
        viewModelScope.launch {
            for ((index, audio) in audioQueue) {
                pendingAudio[index] = audio
                while (pendingAudio.containsKey(nextPlaybackIndex)) {
                    val data = pendingAudio.remove(nextPlaybackIndex)!!
                    if (!_ttsEnabled.value || dropQueuedAudio) {
                        nextPlaybackIndex++
                        continue
                    }
                    try {
                        audioPlayer.prepare(data)
                        audioPlayer.playBlocking()
                    } catch (e: Exception) {
                        DebugLogger.log("Audio playback error: ${e.localizedMessage}")
                    }
                    nextPlaybackIndex++
                }
            }
        }

        val downloadedModels = modelManager.models.filter { it.isDownloaded }
        _availableModels.value = downloadedModels
        val startingModel = downloadedModels.find { it.id == initialModelId } ?: downloadedModels.firstOrNull()
        startingModel?.let { setActiveModel(it, persistConversation = false) }

        refreshConversations()
    }

    fun toggleTtsEnabled() {
        val enabled = !_ttsEnabled.value
        _ttsEnabled.value = enabled
        SettingsManager.setTtsEnabled(context, enabled)
        if (!enabled) {
            stopPlayback()
        } else {
            dropQueuedAudio = false
        }
    }

    fun stopPlayback() {
        dropQueuedAudio = true
        pendingAudio.clear()
        drainAudioQueue()
        audioPlayer.stop()
        _playerState.value = PlayerState.IDLE
        _isSynthesizing.value = false
    }

    fun selectConversation(conversationId: Long) {
        if (_activeConversationId.value == conversationId) return
        loadConversation(conversationId)
    }

    fun createConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = ConversationRepository.createConversation(
                context,
                generateConversationTitle(),
                _activeModel.value?.id,
                emptyList()
            )
            refreshConversations(conversation.id)
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAt = ConversationRepository.renameConversation(context, conversationId, title)
            withContext(Dispatchers.Main) {
                updateConversationSummary(conversationId) { summary ->
                    summary.copy(title = title, updatedAt = updatedAt)
                }
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ConversationRepository.deleteConversation(context, conversationId)
            refreshConversations(
                desiredActiveId = if (_activeConversationId.value == conversationId) null else _activeConversationId.value
            )
        }
    }

    fun selectModel(modelId: String) {
        val model = _availableModels.value.find { it.id == modelId }
            ?: modelManager.getModel(modelId)
        if (model != null && model.isDownloaded) {
            setActiveModel(model)
        }
    }

    fun sendMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        val inference = llmInference ?: run {
            DebugLogger.log("No LLM inference instance available; ignoring message")
            return
        }
        val conversationId = _activeConversationId.value ?: run {
            DebugLogger.log("No active conversation; ignoring message")
            return
        }

        dropQueuedAudio = false
        DebugLogger.log("ChatViewModel sendMessage: $trimmed")
        _chatMessages.value += ChatMessage(trimmed, true)
        conversationHistory.add(ConversationTurn(ConversationRole.USER, trimmed))
        persistConversationMessages()
        _isLoading.value = true

        val benchmarkEnabled = SettingsManager.isBenchmark(context)
        if (benchmarkEnabled) {
            BenchmarkManager.startLlm()
        }

        val responseBuilder = StringBuilder()
        val sentenceBuilder = StringBuilder()
        _chatMessages.value += ChatMessage("...", false) // placeholder

        val conversationForModel = prepareConversationForModel(DEFAULT_MAX_CONTEXT_TOKENS)

        viewModelScope.launch(Dispatchers.IO) {
            inference.sendMessage(conversationForModel) { partial, done ->
                if (benchmarkEnabled) {
                    if (!done) {
                        BenchmarkManager.recordPartial(partial)
                    } else {
                        BenchmarkManager.finishLlm()
                        BenchmarkManager.profileSystem(context)
                    }
                }
                if (!done) {
                    responseBuilder.append(partial)
                    sentenceBuilder.append(partial)
                    viewModelScope.launch {
                        val last = _chatMessages.value.lastOrNull()
                        if (last != null) {
                            _chatMessages.value =
                                _chatMessages.value.dropLast(1) + last.copy(message = responseBuilder.toString())
                        }
                    }
                    processSentences(sentenceBuilder, false)
                } else {
                    viewModelScope.launch {
                        _isLoading.value = false
                        DebugLogger.log("ChatViewModel response complete")
                        val finalResponse = responseBuilder.toString()
                        val last = _chatMessages.value.lastOrNull()
                        if (last != null) {
                            _chatMessages.value =
                                _chatMessages.value.dropLast(1) + last.copy(message = finalResponse)
                        }
                        conversationHistory.add(ConversationTurn(ConversationRole.AGENT, finalResponse))
                        persistConversationMessages()
                        processSentences(sentenceBuilder, true)
                    }
                }
            }
        }
    }

    private fun refreshConversations(desiredActiveId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            var summaries = ConversationRepository.getConversationSummaries(context)
                .sortedByDescending { it.updatedAt }
            var activeId = desiredActiveId ?: _activeConversationId.value
            if (activeId == null || summaries.none { it.id == activeId }) {
                activeId = summaries.firstOrNull()?.id
            }
            if (activeId == null) {
                val conversation = ConversationRepository.createConversation(
                    context,
                    generateConversationTitle(),
                    _activeModel.value?.id,
                    emptyList()
                )
                summaries = ConversationRepository.getConversationSummaries(context)
                    .sortedByDescending { it.updatedAt }
                activeId = conversation.id
            }
            val conversation = ConversationRepository.getConversation(context, activeId)
            withContext(Dispatchers.Main) {
                _conversationSummaries.value = summaries
                _activeConversationId.value = activeId
                applyConversation(conversation)
            }
            conversation?.modelId?.let { modelId ->
                val model = _availableModels.value.find { it.id == modelId && it.isDownloaded }
                    ?: modelManager.getModel(modelId)?.takeIf { it.isDownloaded }
                if (model != null) {
                    withContext(Dispatchers.Main) {
                        setActiveModel(model, persistConversation = false)
                    }
                }
            }
        }
    }

    private fun loadConversation(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = ConversationRepository.getConversation(context, conversationId)
            withContext(Dispatchers.Main) {
                _activeConversationId.value = conversationId
                applyConversation(conversation)
            }
            conversation?.modelId?.let { modelId ->
                val model = _availableModels.value.find { it.id == modelId && it.isDownloaded }
                    ?: modelManager.getModel(modelId)?.takeIf { it.isDownloaded }
                if (model != null) {
                    withContext(Dispatchers.Main) {
                        setActiveModel(model, persistConversation = false)
                    }
                }
            }
        }
    }

    private fun updateConversationSummary(
        conversationId: Long,
        transformer: (ConversationSummary) -> ConversationSummary
    ) {
        val current = _conversationSummaries.value.toMutableList()
        val index = current.indexOfFirst { it.id == conversationId }
        if (index >= 0) {
            val updated = transformer(current[index])
            current.removeAt(index)
            current.add(updated)
            current.sortByDescending { it.updatedAt }
            _conversationSummaries.value = current
        } else {
            refreshConversations(conversationId)
        }
    }

    private fun applyConversation(conversation: Conversation?) {
        conversationHistory.clear()
        if (conversation != null) {
            conversationHistory.addAll(conversation.messages)
            _chatMessages.value = conversation.messages.map { ChatMessage(it.content, it.role == ConversationRole.USER) }
        } else {
            _chatMessages.value = emptyList()
        }
        clearPendingAudio()
    }

    private fun clearPendingAudio() {
        lineIndex = 0
        nextPlaybackIndex = 0
        pendingAudio.clear()
        dropQueuedAudio = false
        drainAudioQueue()
        audioPlayer.stop()
        _playerState.value = PlayerState.IDLE
        _isSynthesizing.value = false
        _isLoading.value = false
    }

    private fun setActiveModel(model: Model, persistConversation: Boolean = true) {
        if (_activeModel.value?.id == model.id && llmInference != null) {
            _activeModel.value = model
            return
        }
        _activeModel.value = model
        llmInference?.close()
        val modelFile = File(context.filesDir, "models/${model.id}.task")
        if (!modelFile.exists()) {
            DebugLogger.log("Model file not found: ${modelFile.absolutePath}")
            llmInference = null
            return
        }
        val inference = LlmInference(context, modelFile.absolutePath)
        inference.initialize()
        llmInference = inference
        if (persistConversation) {
            val conversationId = _activeConversationId.value
            if (conversationId != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val updatedAt = ConversationRepository.updateModel(context, conversationId, model.id)
                    withContext(Dispatchers.Main) {
                        updateConversationSummary(conversationId) { summary ->
                            summary.copy(modelId = model.id, updatedAt = updatedAt)
                        }
                    }
                }
            }
        }
    }

    private fun persistConversationMessages() {
        val conversationId = _activeConversationId.value ?: return
        val messagesSnapshot = conversationHistory.toList()
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAt = ConversationRepository.updateMessages(context, conversationId, messagesSnapshot)
            withContext(Dispatchers.Main) {
                updateConversationSummary(conversationId) { summary ->
                    summary.copy(modelId = _activeModel.value?.id ?: summary.modelId, updatedAt = updatedAt)
                }
            }
        }
    }

    private fun generateConversationTitle(): String {
        val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        return "Conversation ${formatter.format(Date())}"
    }

    private fun prepareConversationForModel(maxTokens: Int): List<LlmMessage> {
        val trimmedConversation = trimConversationToTokenLimit(conversationHistory, maxTokens)
        val totalTokens = trimmedConversation.sumOf { estimateTokenCount(it.content) }
        DebugLogger.log("Prepared ${trimmedConversation.size} conversation turns (~${totalTokens} tokens) for inference")
        return trimmedConversation.map { turn ->
            val role = when (turn.role) {
                ConversationRole.USER -> "user"
                ConversationRole.AGENT -> "agent"
            }
            LlmMessage(role = role, content = turn.content)
        }
    }

    private fun trimConversationToTokenLimit(
        conversation: List<ConversationTurn>,
        maxTokens: Int
    ): List<ConversationTurn> {
        if (conversation.isEmpty() || maxTokens <= 0) {
            return emptyList()
        }
        var remainingTokens = maxTokens
        val trimmed = ArrayDeque<ConversationTurn>()
        for (turn in conversation.asReversed()) {
            if (remainingTokens <= 0) break
            val tokenCount = estimateTokenCount(turn.content)
            if (tokenCount <= remainingTokens) {
                trimmed.addFirst(turn)
                remainingTokens -= tokenCount
            } else {
                val truncatedContent = takeLastTokens(turn.content, remainingTokens)
                if (truncatedContent.isNotBlank()) {
                    trimmed.addFirst(turn.copy(content = truncatedContent))
                }
                break
            }
        }
        return trimmed.toList()
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return TOKEN_REGEX.findAll(text).count()
    }

    private fun takeLastTokens(text: String, tokenLimit: Int): String {
        if (tokenLimit <= 0) return ""
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return ""
        if (tokens.size <= tokenLimit) {
            return text.trim()
        }
        return tokens.takeLast(tokenLimit).joinToString(" ")
    }

    private fun processSentences(builder: StringBuilder, done: Boolean) {
        var text = builder.toString()
        val regex = Regex("[.!?]+|\n")
        var match = regex.find(text)
        while (match != null) {
            val end = match.range.last + 1
            val sentence = text.substring(0, end).trim()
            DebugLogger.log("Queueing sentence: $sentence")
            synthesizeAndQueue(cleanText(sentence))
            text = text.substring(end)
            match = regex.find(text)
        }
        builder.clear()
        builder.append(text)
        if (done && builder.isNotEmpty()) {
            val sentence = builder.toString().trim()
            builder.clear()
            synthesizeAndQueue(cleanText(sentence))
        }
        if (done) {
            dropQueuedAudio = false
        }
    }

    private fun synthesizeAndQueue(text: String) {
        if (!_ttsEnabled.value || dropQueuedAudio) return
        val currentIndex = lineIndex++
        viewModelScope.launch {
            _isSynthesizing.value = true
            try {
                val benchmark = SettingsManager.isBenchmark(context)
                if (benchmark) {
                    BenchmarkManager.handoff()
                }
                DebugLogger.log("Synthesizing: ${text}")
                val audioData = withContext(Dispatchers.IO) {
                    val mixedVector = mixStyles(
                        styleLoader,
                        _selectedStyles.value,
                        _weights.value,
                        _interpolationMode.value
                    )
                    val engine = SettingsManager.getTtsEngine(context)
                    val ttsStart = SystemClock.elapsedRealtime()
                    val (data, sampleRate) = if (engine == TtsEngine.KITTEN) {
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
                    val genMs = SystemClock.elapsedRealtime() - ttsStart
                    if (benchmark) {
                        val audioMs = data.size * 1000L / sampleRate
                        BenchmarkManager.recordTts(engine, genMs, audioMs)
                        BenchmarkManager.profileSystem(context)
                    }
                    data
                }
                if (!dropQueuedAudio) {
                    audioQueue.send(currentIndex to audioData)
                }
            } catch (e: Exception) {
                DebugLogger.log("Error synthesizing sentence: ${e.localizedMessage}")
            } finally {
                _isSynthesizing.value = false
            }
        }
    }

    private fun drainAudioQueue() {
        while (true) {
            val result = audioQueue.tryReceive()
            if (result.isFailure) break
        }
    }

    private fun cleanText(text: String): String {
        val replaced = text.replace("\\n", "\n").replace("/n", "\n")
        val markdownRegex = Regex("[*_`>#\\[\\](){},]")
        return replaced.replace(markdownRegex, "")
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
        llmInference?.close()
        audioPlayer.stop()
    }
}
