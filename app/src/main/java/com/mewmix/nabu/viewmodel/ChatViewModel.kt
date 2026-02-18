package com.mewmix.nabu.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.chat.ChatMessage
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlamaCppBackend
import com.mewmix.nabu.chat.LlmRuntimeOverrides
import com.mewmix.nabu.chat.MediaPipeBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.data.Conversation
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.data.ConversationRole
import com.mewmix.nabu.data.ConversationSummary
import com.mewmix.nabu.data.ConversationTurn
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.kokoro.KokoroEngine
import com.mewmix.nabu.supertonic.DebugSupertonicEngine
import com.mewmix.nabu.tts.TTSManager
import com.mewmix.nabu.utils.AudioPlayer
import com.mewmix.nabu.utils.BenchmarkManager
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.InterpolationMode
import com.mewmix.nabu.utils.KokoroAudioPlayer
import com.mewmix.nabu.utils.OnnxRuntimeManager
import com.mewmix.nabu.utils.PhonemeConverter
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.SettingsManager
import com.mewmix.nabu.utils.StyleLoader
import com.mewmix.nabu.utils.createAudioFromStyleVector
import com.mewmix.nabu.utils.mixStyles
import com.mewmix.nabu.tools.GlaiveBridge
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolCallProtocol
import com.mewmix.nabu.tools.ToolRegistry
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatViewModel(
    private val context: Context,
    initialModelId: String,
    private val llmOverrides: LlmRuntimeOverrides? = null
) : ViewModel() {

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 1024
        private const val MAX_TOOL_CALLS_PER_TURN = 1
        private const val TOOL_EXECUTION_TIMEOUT_MS = 30_000L
        private val TOKEN_REGEX = Regex("\\S+")
    }

    // Dependencies
    private val phonemeConverter = PhonemeConverter(context)
    val styleLoader = StyleLoader(context)
    private val defaultVoice = styleLoader.names.firstOrNull() ?: "af_sky"
    private val audioPlayer: AudioPlayer = KokoroAudioPlayer(viewModelScope) { newState ->
        _playerState.value = newState
    }

    private val modelManager = ModelManager(context)
    private var llmBackend: LlmBackend? = null
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

    private data class QueuedAudio(val index: Int, val audio: FloatArray, val sampleRate: Int)

    private val audioQueue = Channel<QueuedAudio>(Channel.UNLIMITED)
    private val pendingAudio = mutableMapOf<Int, QueuedAudio>()
    private var nextPlaybackIndex = 0
    private var dropQueuedAudio = false
    private var lineIndex = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            OnnxRuntimeManager.initialize(context.applicationContext)

            // Initialize Glaive tools if available
            if (GlaiveBridge.isInstalled(context.applicationContext)) {
                GlaiveBridge.registerDefaultTools()
                DebugLogger.log("ChatViewModel: Glaive tools registered.")
            }
        }
        // Launch a coroutine to play queued audio in the order they were generated
        viewModelScope.launch {
            for (item in audioQueue) {
                pendingAudio[item.index] = item
                while (pendingAudio.containsKey(nextPlaybackIndex)) {
                    val queued = pendingAudio.remove(nextPlaybackIndex)!!
                    if (!_ttsEnabled.value || dropQueuedAudio) {
                        nextPlaybackIndex++
                        continue
                    }
                    try {
                        audioPlayer.prepare(queued.audio, queued.sampleRate)
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
        refreshStyles()
    }

    fun refreshStyles() {
        val available = styleLoader.names
        if (available.isNotEmpty()) {
            val current = _selectedStyles.value
            if (current.isEmpty() || current.any { it !in available }) {
                val default = available.first()
                _selectedStyles.value = listOf(default)
                _weights.value = mapOf(default to 1f)
            }
        }
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
        val backend = llmBackend ?: run {
            DebugLogger.log("No LLM backend available; ignoring message")
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

        val runtimeConfig = SettingsManager.getLlmRuntimeConfig(context, llmOverrides)
        val backendMaxTokens = when (backend) {
            is LlamaCppBackend -> {
                backend.updateConfig(runtimeConfig)
                backend.currentConfig.nCtx
            }
            is MediaPipeBackend -> {
                val mediaPipeConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
                backend.updateConfig(mediaPipeConfig)
                mediaPipeConfig.maxTokens
            }
            else -> DEFAULT_MAX_CONTEXT_TOKENS
        }

        val conversationForModel = prepareConversationForModel(backendMaxTokens)
        val appContext = context.applicationContext

        viewModelScope.launch(Dispatchers.IO) {
            suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
                val tool = ToolRegistry.getTool(toolCall.toolName)
                if (tool == null || !tool.isAvailable) {
                    return ToolResult(
                        toolName = toolCall.toolName,
                        output = "Tool '${toolCall.toolName}' is unavailable.",
                        isError = true
                    )
                }
                if (!GlaiveBridge.isInstalled(appContext)) {
                    return ToolResult(
                        toolName = toolCall.toolName,
                        output = "Glaive is not installed.",
                        isError = true
                    )
                }
                return withTimeoutOrNull(TOOL_EXECUTION_TIMEOUT_MS) {
                    GlaiveBridge.executeTool(appContext, toolCall)
                } ?: ToolResult(
                    toolName = toolCall.toolName,
                    output = "Tool '${toolCall.toolName}' timed out after ${TOOL_EXECUTION_TIMEOUT_MS / 1000}s.",
                    isError = true
                )
            }

            fun updateAssistantPlaceholder(content: String) {
                viewModelScope.launch {
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(message = content)
                    }
                }
            }

            fun finalizeAssistantResponse(finalResponse: String, speakOutput: Boolean) {
                viewModelScope.launch {
                    _isLoading.value = false
                    DebugLogger.log("ChatViewModel response complete")
                    val last = _chatMessages.value.lastOrNull()
                    if (last != null) {
                        _chatMessages.value =
                            _chatMessages.value.dropLast(1) + last.copy(message = finalResponse)
                    }
                    conversationHistory.add(ConversationTurn(ConversationRole.AGENT, finalResponse))
                    persistConversationMessages()
                    if (speakOutput) {
                        processSentences(sentenceBuilder, true)
                    } else {
                        sentenceBuilder.clear()
                        dropQueuedAudio = false
                    }
                }
            }

            fun runInference(conversation: List<LlmMessage>, remainingToolCalls: Int) {
                responseBuilder.clear()
                sentenceBuilder.clear()
                var suppressSpeechForThisPass = false

                backend.sendMessage(conversation) { partial, done ->
                    if (benchmarkEnabled && !done) {
                        BenchmarkManager.recordPartial(partial)
                    }

                    if (!done) {
                        responseBuilder.append(partial)
                        if (partial.contains("<tool_call>", ignoreCase = true)) {
                            suppressSpeechForThisPass = true
                            sentenceBuilder.clear()
                        } else {
                            sentenceBuilder.append(partial)
                        }
                        updateAssistantPlaceholder(responseBuilder.toString())
                        if (!suppressSpeechForThisPass) {
                            processSentences(sentenceBuilder, false)
                        }
                        return@sendMessage
                    }

                    val finalResponse = responseBuilder.toString()
                    val toolCall = ToolCallProtocol.extractToolCall(finalResponse)
                    if (toolCall != null && remainingToolCalls > 0) {
                        updateAssistantPlaceholder("Running tool ${toolCall.toolName}...")
                        viewModelScope.launch(Dispatchers.IO) {
                            val result = executeToolCall(toolCall)
                            val followUpConversation = conversation + listOf(
                                LlmMessage(role = "model", content = finalResponse),
                                LlmMessage(
                                    role = "user",
                                    content = ToolCallProtocol.formatToolResultForModel(result)
                                )
                            )
                            runInference(followUpConversation, remainingToolCalls - 1)
                        }
                        return@sendMessage
                    }

                    if (benchmarkEnabled) {
                        BenchmarkManager.finishLlm()
                        BenchmarkManager.profileSystem(context)
                    }
                    finalizeAssistantResponse(
                        finalResponse = finalResponse,
                        speakOutput = toolCall == null
                    )
                }
            }

            runInference(conversationForModel, MAX_TOOL_CALLS_PER_TURN)
        }
    }

    fun cancelGeneration() {
        llmBackend?.cancel()
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
        if (_activeModel.value?.id == model.id && llmBackend != null) {
            _activeModel.value = model
            return
        }
        _activeModel.value = model
        llmBackend?.close()

        val taskFile = File(context.filesDir, "models/${model.id}.task")
        val ggufFile = File(context.filesDir, "models/${model.id}.gguf")

        // Use backend set by ModelManager, or fallback to file existence check
        val isLlama = model.backend == "llama" || (ggufFile.exists() && !taskFile.exists())

        if (isLlama) {
            if (!ggufFile.exists()) {
                DebugLogger.log("GGUF Model file not found: ${ggufFile.absolutePath}")
                llmBackend = null
                return
            }
            val runtimeConfig = SettingsManager.getLlmRuntimeConfig(context, llmOverrides)
            val backend = LlamaCppBackend(context, ggufFile.absolutePath, runtimeConfig)
            llmBackend = backend
            viewModelScope.launch(Dispatchers.IO) {
                backend.initialize()
            }
        } else {
            if (!taskFile.exists()) {
                DebugLogger.log("Task Model file not found: ${taskFile.absolutePath}")
                llmBackend = null
                return
            }
            val backend = MediaPipeBackend(
                context = context,
                modelPath = taskFile.absolutePath,
                initialConfig = SettingsManager.getMediaPipeRuntimeConfig(context)
            )
            backend.initialize()
            llmBackend = backend
        }
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

    // System Prompt & Token Usage
    private val _systemPrompt = MutableStateFlow("You are a helpful AI assistant.")
    val systemPrompt = _systemPrompt.asStateFlow()

    private val _tokenUsage = MutableStateFlow(0 to DEFAULT_MAX_CONTEXT_TOKENS)
    val tokenUsage = _tokenUsage.asStateFlow()

    fun updateSystemPrompt(newPrompt: String) {
        _systemPrompt.value = newPrompt
    }

    private fun prepareConversationForModel(maxTokens: Int): List<LlmMessage> {
        val availableTools = ToolRegistry.tools.value.filter { it.isAvailable }
        val systemPrompt = ToolCallProtocol.buildSystemPrompt(
            basePrompt = _systemPrompt.value,
            tools = availableTools
        )
        val systemMsg = LlmMessage(role = "system", content = systemPrompt)
        val systemTokens = estimateTokenCount(systemMsg.content)
        val availableTokens = maxTokens - systemTokens

        val trimmedConversation = trimConversationToTokenLimit(conversationHistory, availableTokens)
        val totalTokens = trimmedConversation.sumOf { estimateTokenCount(it.content) } + systemTokens
        
        _tokenUsage.value = totalTokens to maxTokens
        DebugLogger.log(
            "Prepared ${trimmedConversation.size} conversation turns + system prompt " +
                "(~${totalTokens} tokens, tools=${availableTools.size}) for inference"
        )
        
        val messages = mutableListOf<LlmMessage>()
        if (systemMsg.content.isNotBlank()) {
            messages.add(systemMsg)
        }
        
        messages.addAll(trimmedConversation.map { turn ->
            val role = when (turn.role) {
                ConversationRole.USER -> "user"
                ConversationRole.AGENT -> "model" // Changed from "agent" to "model"
            }
            LlmMessage(role = role, content = turn.content)
        })
        return messages
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
                    val engine = TTSManager.getEngine(context, modelManager)
                        ?: throw IllegalStateException("No TTS engine available")

                    val ttsStart = SystemClock.elapsedRealtime()

                    val realEngine = if (engine is com.mewmix.nabu.tts.BenchmarkingTTSEngine) engine.delegate else engine

                    if (realEngine is KokoroEngine) {
                        DebugLogger.log("ChatViewModel: Using KokoroEngine. Phonemizing '$text'...")
                    } else {
                        DebugLogger.log("ChatViewModel: Using ${realEngine.name}. Synthesizing '$text'...")
                    }

                    val (data, sampleRate) = if (realEngine is KokoroEngine) {
                        val mixedVector = mixStyles(
                            styleLoader,
                            _selectedStyles.value,
                            _weights.value,
                            _interpolationMode.value
                        )
                        val phonemes = phonemeConverter.phonemize(text)
                        DebugLogger.log("ChatViewModel: Phonemes generated: $phonemes")

                        createAudioFromStyleVector(
                            phonemes = phonemes,
                            voice = mixedVector,
                            speed = _speed.value,
                            engine = realEngine
                        )
                    } else {
                        // For Supertonic or other engines, use the interface directly
                        // Note: Supertonic does its own text normalization/phonemization internally or via TextProcessor
                        if (realEngine is DebugSupertonicEngine) {
                            val styleName = _selectedStyles.value.firstOrNull() ?: "F1"
                            DebugLogger.log("ChatViewModel: Setting Supertonic style to '$styleName'")
                            realEngine.setStyle(styleName)
                        }
                        val result = engine.synthesize(text, _speed.value)
                        result.wav to result.sampleRate
                    }

                    val genMs = SystemClock.elapsedRealtime() - ttsStart
                    if (benchmark) {
                        val audioMs = data.size * 1000L / sampleRate
                        // Note: Benchmark might need adjustment for Supertonic details
                        BenchmarkManager.recordTts(OnnxRuntimeManager.currentBundle(), genMs, audioMs)
                        BenchmarkManager.profileSystem(context)
                    }
                    QueuedAudio(currentIndex, data, sampleRate)
                }
                if (!dropQueuedAudio) {
                    audioQueue.send(audioData)
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
        llmBackend?.close()
        audioPlayer.stop()
    }
}
